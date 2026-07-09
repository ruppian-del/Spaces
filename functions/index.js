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

  const title = typeof notification.title === "string" && notification.title.trim().length
    ? notification.title.trim()
    : "New update in Spaces";
  const body = typeof notification.subtitle === "string" && notification.subtitle.trim().length
    ? notification.subtitle.trim()
    : (typeof notification.spaceName === "string" && notification.spaceName.trim().length
      ? notification.spaceName.trim()
      : "Open Spaces to view this notification");

  const dataPayload = {
    notificationId,
    spaceId: stringValue(notification.spaceId),
    targetId: stringValue(notification.targetId),
    targetType,
    type: notificationType,
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
          },
        },
      },
      android: {
        priority: "high",
        notification: {
          sound: "default",
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

async function updateNotificationDeliveryState(notificationId, delivered, deliveryError) {
  const payload = {
    delivered,
    deliveredAt: delivered ? admin.firestore.FieldValue.serverTimestamp() : null,
    deliveryError: deliveryError || null,
  };
  await admin.firestore().collection("notifications").doc(notificationId).set(payload, { merge: true });
}
