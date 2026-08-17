const admin = require("firebase-admin");
const functions = require("firebase-functions/v1");
const logger = require("firebase-functions/logger");

if (!admin.apps.length) {
  admin.initializeApp();
}

exports.sendNotificationPush = functions.firestore.document("notifications/{notificationId}").onCreate(async (snapshot, context) => {
  if (!snapshot) {
    logger.warn("Missing notification snapshot", { params: context.params });
    return;
  }

  const notification = snapshot.data();
  const notificationId = context.params.notificationId;
  const recipientId = typeof notification.recipientId === "string" ? notification.recipientId.trim() : "";
  const actorId = typeof notification.actorId === "string" ? notification.actorId.trim() : "";
  const notificationType = stringValue(notification.type);
  const targetType = stringValue(notification.targetType);
  if (!recipientId) {
    logger.warn("Notification missing recipientId", { notificationId, notification });
    return;
  }
  if (recipientId === actorId) {
    return;
  }

  const pushTokenQuery = admin
    .firestore()
    .collection("users")
    .doc(recipientId)
    .collection("pushTokens")
    .where("enabled", "==", true);

  const pushTokensSnapshot = await pushTokenQuery.get();

  if (pushTokensSnapshot.empty) {
    await updateNotificationDeliveryState(notificationId, false, "No enabled push tokens found.")
    logger.info("No enabled push tokens for recipient", { notificationId, recipientId });
    return;
  }

  const pushTokenDocs = pushTokensSnapshot.docs
    .map((doc) => ({ id: doc.id, ...doc.data() }))
    .filter((doc) => typeof doc.token === "string" && doc.token.trim().length > 0);

  if (!pushTokenDocs.length) {
    await updateNotificationDeliveryState(notificationId, false, "Enabled push token documents were missing valid token strings.")
    logger.info("No valid push tokens for recipient", { notificationId, recipientId });
    return;
  }

  const actionTitle = typeof notification.title === "string" && notification.title.trim().length
    ? notification.title.trim()
    : "New update in Spaces";
  const actorName = await resolveActorName(notification, actorId);
  const title = actorName && !actionTitle.toLocaleLowerCase().startsWith(actorName.toLocaleLowerCase())
    ? `${actorName} ${actionTitle}`
    : actionTitle;
  const body = typeof notification.subtitle === "string" && notification.subtitle.trim().length
    ? notification.subtitle.trim()
    : (typeof notification.spaceName === "string" && notification.spaceName.trim().length
      ? `In ${notification.spaceName.trim()}`
      : "Open Spaces to view this notification");

  const recipientNotifications = await admin
    .firestore()
    .collection("notifications")
    .where("recipientId", "==", recipientId)
    .get();
  const unreadCount = recipientNotifications.docs.filter((document) => document.get("read") !== true).length;

  const dataPayload = {
    notificationId,
    spaceId: stringValue(notification.spaceId),
    targetId: stringValue(notification.targetId),
    targetType,
    type: notificationType,
    badgeCount: String(unreadCount),
  };

  let response;
  try {
    response = await admin.messaging().sendEachForMulticast({
      tokens: pushTokenDocs.map((doc) => doc.token.trim()),
      notification: {
        title,
        body,
      },
      data: dataPayload,
      apns: {
        payload: {
          aps: {
            sound: "default",
            badge: unreadCount,
          },
        },
      },
      android: {
        priority: "high",
        notification: {
          sound: "default",
          notificationCount: unreadCount,
        },
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    logger.error("FCM multicast send threw", {
      notificationId,
      recipientId,
      errorMessage: message,
    });
    await updateNotificationDeliveryState(notificationId, false, message);
    return;
  }

  const invalidCodes = new Set([
    "messaging/invalid-registration-token",
    "messaging/registration-token-not-registered",
    "messaging/invalid-argument",
  ]);

  const disableTasks = [];
  let successCount = 0;
  const failureMessages = [];
  response.responses.forEach((sendResponse, index) => {
    const tokenDoc = pushTokenDocs[index];
    if (sendResponse.success) {
      successCount += 1;
      logger.info("FCM send success", {
        notificationId,
        recipientId,
        tokenId: tokenDoc?.id || null,
        platform: typeof tokenDoc?.platform === "string" ? tokenDoc.platform : "unknown",
      });
      return;
    }

    const errorCode = sendResponse.error?.code || "unknown";
    const errorMessage = sendResponse.error?.message || "Unknown FCM send failure";
    failureMessages.push(`${tokenDoc?.id || "unknown"}:${errorCode}:${errorMessage}`);
    logger.warn("Push send failed", {
      notificationId,
      recipientId,
      tokenId: tokenDoc?.id,
      platform: typeof tokenDoc?.platform === "string" ? tokenDoc.platform : "unknown",
      errorCode,
      errorMessage,
    });

    if (!invalidCodes.has(errorCode)) {
      return;
    }

    const tokenDocId = tokenDoc?.id;
    if (!tokenDocId) {
      return;
    }

    disableTasks.push(
      admin
        .firestore()
        .collection("users")
        .doc(recipientId)
        .collection("pushTokens")
        .doc(tokenDocId)
        .set(
          {
            enabled: false,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
          { merge: true }
        )
    );
  });

  await Promise.all(disableTasks);
  const delivered = successCount > 0;
  await updateNotificationDeliveryState(
    notificationId,
    delivered,
    delivered ? null : failureMessages.join(" | ")
  );
});

function stringValue(value) {
  return typeof value === "string" ? value : "";
}

exports.deleteAccount = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (_data, context) => {
    const uid = context.auth?.uid;
    if (!uid) {
      throw new functions.https.HttpsError(
        "unauthenticated",
        "You must be signed in to delete your account."
      );
    }

    const firestore = admin.firestore();

    try {
      await removeUserFromSpaces(firestore, uid);
      await deleteQueryDocuments(
        firestore.collection("notifications").where("recipientId", "==", uid)
      );
      await anonymizeQueryDocuments(
        firestore.collection("notifications").where("actorId", "==", uid),
        {
          actorId: "deleted-user",
          actorName: "Deleted User",
          actorEmoji: "",
        }
      );
      await anonymizeQueryDocuments(
        firestore.collection("activity").where("actorId", "==", uid),
        {
          actorId: "deleted-user",
          actorName: "Deleted User",
          actorEmoji: "",
        }
      );
      await removeActivityVisibility(firestore, uid);
      await deactivateInvitesCreatedBy(firestore, uid);

      // recursiveDelete removes the profile plus devices, push tokens, and any
      // future user-owned subcollections without requiring client permissions.
      await firestore.recursiveDelete(firestore.collection("users").doc(uid));

      // Delete Auth last. Every preceding operation is safe to retry if this
      // callable is interrupted before completion.
      try {
        await admin.auth().deleteUser(uid);
      } catch (error) {
        if (error?.code !== "auth/user-not-found") {
          throw error;
        }
      }

      return { deleted: true };
    } catch (error) {
      logger.error("Account deletion failed", {
        uid,
        errorMessage: error instanceof Error ? error.message : String(error),
      });
      throw new functions.https.HttpsError(
        "internal",
        "Spaces could not finish deleting your account. Your account remains available so you can retry."
      );
    }
  });

async function removeUserFromSpaces(firestore, uid) {
  const spacesSnapshot = await firestore
    .collection("spaces")
    .where("memberIds", "array-contains", uid)
    .get();

  for (const spaceDocument of spacesSnapshot.docs) {
    const spaceReference = spaceDocument.ref;
    const spaceData = spaceDocument.data();
    const membersSnapshot = await spaceReference.collection("members").get();
    const remainingMembers = membersSnapshot.docs.filter((document) => document.id !== uid);

    if (remainingMembers.length === 0) {
      await firestore.recursiveDelete(spaceReference);
      continue;
    }

    const update = {
      memberIds: admin.firestore.FieldValue.arrayRemove(uid),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    };

    if (stringValue(spaceData.ownerId) === uid) {
      const replacementOwner = remainingMembers.find(
        (document) => stringValue(document.data().role).toLowerCase() === "admin"
      ) || remainingMembers[0];
      update.ownerId = replacementOwner.id;
      await replacementOwner.ref.set(
        {
          role: "owner",
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );
    }

    await spaceReference.set(update, { merge: true });
    await spaceReference.collection("members").doc(uid).delete();
  }
}

async function deleteQueryDocuments(query) {
  const snapshot = await query.get();
  await commitInChunks(snapshot.docs.map((document) => ({
    type: "delete",
    reference: document.ref,
  })));
}

async function anonymizeQueryDocuments(query, fields) {
  const snapshot = await query.get();
  await commitInChunks(snapshot.docs.map((document) => ({
    type: "set",
    reference: document.ref,
    fields,
  })));
}

async function removeActivityVisibility(firestore, uid) {
  const snapshot = await firestore
    .collection("activity")
    .where("visibleTo", "array-contains", uid)
    .get();
  await commitInChunks(snapshot.docs.map((document) => ({
    type: "set",
    reference: document.ref,
    fields: {
      visibleTo: admin.firestore.FieldValue.arrayRemove(uid),
    },
  })));
}

async function deactivateInvitesCreatedBy(firestore, uid) {
  const snapshot = await firestore
    .collection("spaceInvites")
    .where("createdBy", "==", uid)
    .get();
  await commitInChunks(snapshot.docs.map((document) => ({
    type: "set",
    reference: document.ref,
    fields: {
      active: false,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
  })));
}

async function commitInChunks(operations) {
  const firestore = admin.firestore();
  for (let index = 0; index < operations.length; index += 400) {
    const batch = firestore.batch();
    operations.slice(index, index + 400).forEach((operation) => {
      if (operation.type === "delete") {
        batch.delete(operation.reference);
      } else {
        batch.set(operation.reference, operation.fields, { merge: true });
      }
    });
    await batch.commit();
  }
}

async function updateNotificationDeliveryState(notificationId, delivered, deliveryError) {
  const payload = {
    delivered,
    deliveredAt: delivered ? admin.firestore.FieldValue.serverTimestamp() : null,
    deliveryError: deliveryError || null,
  };
  await admin.firestore().collection("notifications").doc(notificationId).set(payload, { merge: true });
}

async function resolveActorName(notification, actorId) {
  const storedName = stringValue(notification.actorName).trim();
  if (storedName) return storedName;
  if (!actorId) return "Someone";

  try {
    const profile = await admin.firestore().collection("users").doc(actorId).get();
    const profileName = stringValue(profile.get("displayName")).trim();
    if (profileName) return profileName;
  } catch (error) {
    logger.warn("Unable to resolve notification actor name", {
      actorId,
      errorMessage: error instanceof Error ? error.message : String(error),
    });
  }

  return "Someone";
}
