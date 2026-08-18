const admin = require("firebase-admin");
const functions = require("firebase-functions/v1");
const logger = require("firebase-functions/logger");
const Stripe = require("stripe");

if (!admin.apps.length) {
  admin.initializeApp();
}

const STRIPE_PRICE_IDS = Object.freeze({
  foundation: "price_1U5WUb2XkCagCZO4Cqcmmv9O",
  people250: "price_1U5Wfy2XkCagCZO4iMT4Oc8j",
  spaces10: "price_1U5WgH2XkCagCZO49sl5pHCW",
  communication: "price_1U5Wge2XkCagCZO4tupgeGFg",
  agenda: "price_1U5Wh42XkCagCZO4OmI9YRLG",
  content: "price_1U5Wq12XkCagCZO4ZUZYABag",
  storage100: "price_1U5Wsr2XkCagCZO4EuFx52se",
  storage500: "price_1U5WtR2XkCagCZO4FyiQkoCo",
});
const PAID_MODULE_PRICE_IDS = Object.freeze({
  communication: STRIPE_PRICE_IDS.communication,
  agenda: STRIPE_PRICE_IDS.agenda,
  content: STRIPE_PRICE_IDS.content,
});
const FREE_ORGANIZATION_ENTITLEMENTS = Object.freeze({
  peopleCapacity: 25,
  activeSpaceCapacity: 1,
  enabledModuleIds: ["general", "events", "polls", "activity", "members", "settings"],
  mediaStorageCapacityBytes: 1024 ** 3,
});
const FOUNDATION_MODULE_IDS = Object.freeze(["general", "events", "polls", "activity", "members", "settings"]);

exports.downloadWorkspaceAsset = functions.https.onRequest(async (request, response) => {
  if (request.method !== "GET") {
    response.status(405).send("Method not allowed");
    return;
  }

  const token = stringValue(request.header("authorization")).match(/^Bearer\s+(.+)$/i)?.[1];
  const spaceId = stringValue(request.query.spaceId).trim();
  const fileId = stringValue(request.query.fileId).trim();
  if (!token || !spaceId || !fileId) {
    response.status(400).send("A signed-in user, Space, and file are required.");
    return;
  }

  try {
    const decodedToken = await admin.auth().verifyIdToken(token);
    const firestore = admin.firestore();
    const spaceRef = firestore.collection("spaces").doc(spaceId);
    const [spaceSnapshot, memberSnapshot, fileSnapshot] = await Promise.all([
      spaceRef.get(),
      spaceRef.collection("members").doc(decodedToken.uid).get(),
      spaceRef.collection("files").doc(fileId).get(),
    ]);
    if (!spaceSnapshot.exists || !fileSnapshot.exists || fileSnapshot.get("deleted") === true) {
      response.status(404).send("The requested file is unavailable.");
      return;
    }

    const memberRole = stringValue(memberSnapshot.get("role"));
    let authorized = memberRole === "owner" || memberRole === "admin";
    const organizationId = stringValue(spaceSnapshot.get("organizationId")).trim();
    if (!authorized && organizationId) {
      const organizationMember = await firestore.collection("organizations").doc(organizationId).collection("members").doc(decodedToken.uid).get();
      const organizationRole = stringValue(organizationMember.get("role"));
      authorized = organizationRole === "primary_admin" || organizationRole === "admin";
    }
    if (!authorized) {
      response.status(403).send("You do not have access to this Space file.");
      return;
    }

    const storagePath = stringValue(fileSnapshot.get("storagePath")).trim();
    if (!storagePath) {
      response.status(404).send("This file does not have a stored asset.");
      return;
    }
    const file = admin.storage().bucket().file(storagePath);
    const [metadata] = await file.getMetadata();
    response.status(200);
    response.set("Cache-Control", "private, no-store");
    response.set("Content-Type", "text/plain; charset=utf-8");
    if (metadata.size) response.set("Content-Length", metadata.size);
    file.createReadStream()
      .on("error", (error) => {
        logger.error("Unable to stream Workspace file", { spaceId, fileId, errorMessage: error.message });
        if (!response.headersSent) response.status(502).send("The stored file could not be read.");
        else response.destroy(error);
      })
      .pipe(response);
  } catch (error) {
    logger.error("Workspace file download failed", { spaceId, fileId, errorMessage: error instanceof Error ? error.message : String(error) });
    if (!response.headersSent) response.status(500).send("The file could not be loaded.");
  }
});

exports.createOrganizationCheckout = functions
  .runWith({ secrets: ["STRIPE_SECRET_KEY"] })
  .https.onCall(async (data, context) => {
    const uid = context.auth?.uid;
    const organizationId = stringValue(data?.organizationId).trim();
    if (!uid) {
      throw new functions.https.HttpsError("unauthenticated", "You must be signed in to manage billing.");
    }
    if (!organizationId) {
      throw new functions.https.HttpsError("invalid-argument", "An organization is required.");
    }

    const firestore = admin.firestore();
    const [organizationSnapshot, memberSnapshot] = await Promise.all([
      firestore.collection("organizations").doc(organizationId).get(),
      firestore.collection("organizations").doc(organizationId).collection("members").doc(uid).get(),
    ]);
    if (!organizationSnapshot.exists || !memberSnapshot.exists || memberSnapshot.get("role") !== "primary_admin") {
      throw new functions.https.HttpsError("permission-denied", "Only the primary administrator can manage billing.");
    }

    const selection = normalizeSubscriptionSelection(data?.selection);
    const returnOrigin = checkoutReturnOrigin(data?.returnOrigin, context);
    const stripeSecretKey = process.env.STRIPE_SECRET_KEY;
    if (!stripeSecretKey) {
      logger.error("Stripe secret is not bound to createOrganizationCheckout.");
      throw new functions.https.HttpsError("failed-precondition", "Billing is not configured yet.");
    }
    const stripe = new Stripe(stripeSecretKey);
    const organization = organizationSnapshot.data() || {};
    const existingCustomerId = stringValue(organization.billing?.stripeCustomerId).trim();
    const existingSubscription = await subscriptionForOrganization(stripe, organizationId, organization);
    if (existingSubscription && !existingSubscription.deleted && (existingSubscription.status === "active" || existingSubscription.status === "trialing")) {
      const updatedSubscription = await stripe.subscriptions.update(existingSubscription.id, { items: subscriptionUpdateItems(existingSubscription, selection) });
      await synchronizeStripeSubscription(updatedSubscription);
      return { url: null, updated: true };
    }
    const customer = existingCustomerId
      ? await stripe.customers.retrieve(existingCustomerId)
      : await stripe.customers.create({
        email: stringValue(context.auth.token.email).trim() || undefined,
        name: stringValue(organization.name).trim() || undefined,
        metadata: { organizationId },
      });
    if (customer.deleted) {
      throw new functions.https.HttpsError("failed-precondition", "The billing customer is no longer available.");
    }
    if (!existingCustomerId) {
      await organizationSnapshot.ref.set({
        billing: { stripeCustomerId: customer.id, createdAt: admin.firestore.FieldValue.serverTimestamp() },
      }, { merge: true });
    }

    const session = await stripe.checkout.sessions.create({
      mode: "subscription",
      customer: customer.id,
      client_reference_id: organizationId,
      line_items: checkoutLineItems(selection),
      success_url: `${returnOrigin}/organizations/${organizationId}/billing?checkout=success`,
      cancel_url: `${returnOrigin}/organizations/${organizationId}/billing?checkout=cancelled`,
      metadata: { organizationId, initiatedBy: uid },
      subscription_data: { metadata: { organizationId, initiatedBy: uid } },
    });
    if (!session.url) {
      throw new functions.https.HttpsError("internal", "Stripe did not return a checkout link.");
    }
    return { url: session.url, updated: false };
  });

exports.getOrganizationSubscription = functions
  .runWith({ secrets: ["STRIPE_SECRET_KEY"] })
  .https.onCall(async (data, context) => {
    const uid = context.auth?.uid;
    const organizationId = stringValue(data?.organizationId).trim();
    if (!uid || !organizationId) throw new functions.https.HttpsError("invalid-argument", "A signed-in organization administrator is required.");
    const organizationRef = admin.firestore().collection("organizations").doc(organizationId);
    const [organizationSnapshot, memberSnapshot] = await Promise.all([organizationRef.get(), organizationRef.collection("members").doc(uid).get()]);
    if (!organizationSnapshot.exists || !memberSnapshot.exists || memberSnapshot.get("role") !== "primary_admin") throw new functions.https.HttpsError("permission-denied", "Only the primary administrator can manage billing.");
    const stripe = new Stripe(process.env.STRIPE_SECRET_KEY);
    const subscription = await subscriptionForOrganization(stripe, organizationSnapshot.id, organizationSnapshot.data() || {});
    if (!subscription || subscription.deleted || (subscription.status !== "active" && subscription.status !== "trialing")) return { selection: null };
    await synchronizeStripeSubscription(subscription);
    return {
      selection: selectionFromSubscription(subscription),
      status: subscription.status,
      cancelAtPeriodEnd: subscription.cancel_at_period_end === true,
      currentPeriodEnd: subscriptionPeriodEnd(subscription),
    };
  });

exports.cancelOrganizationSubscription = functions
  .runWith({ secrets: ["STRIPE_SECRET_KEY"] })
  .https.onCall(async (data, context) => {
    const uid = context.auth?.uid;
    const organizationId = stringValue(data?.organizationId).trim();
    if (!uid || !organizationId) throw new functions.https.HttpsError("invalid-argument", "A signed-in organization administrator is required.");
    const organizationRef = admin.firestore().collection("organizations").doc(organizationId);
    const [organizationSnapshot, memberSnapshot] = await Promise.all([organizationRef.get(), organizationRef.collection("members").doc(uid).get()]);
    if (!organizationSnapshot.exists || !memberSnapshot.exists || memberSnapshot.get("role") !== "primary_admin") throw new functions.https.HttpsError("permission-denied", "Only the primary administrator can manage billing.");
    const stripe = new Stripe(process.env.STRIPE_SECRET_KEY);
    const existingSubscription = await subscriptionForOrganization(stripe, organizationSnapshot.id, organizationSnapshot.data() || {});
    if (!existingSubscription) return { cancelled: false };
    const subscription = await stripe.subscriptions.update(existingSubscription.id, { cancel_at_period_end: true });
    await synchronizeStripeSubscription(subscription);
    return { cancelled: true };
  });

exports.refreshOrganizationSubscription = functions
  .runWith({ secrets: ["STRIPE_SECRET_KEY"] })
  .https.onCall(async (data, context) => {
    const uid = context.auth?.uid;
    const organizationId = stringValue(data?.organizationId).trim();
    if (!uid || !organizationId) {
      throw new functions.https.HttpsError("invalid-argument", "A signed-in organization administrator is required.");
    }
    const organizationRef = admin.firestore().collection("organizations").doc(organizationId);
    const [organizationSnapshot, memberSnapshot] = await Promise.all([
      organizationRef.get(),
      organizationRef.collection("members").doc(uid).get(),
    ]);
    if (!organizationSnapshot.exists || !memberSnapshot.exists || memberSnapshot.get("role") !== "primary_admin") {
      throw new functions.https.HttpsError("permission-denied", "Only the primary administrator can manage billing.");
    }
    const subscriptionId = stringValue(organizationSnapshot.get("billing.stripeSubscriptionId")).trim();
    if (!subscriptionId) return { refreshed: false };
    const stripe = new Stripe(process.env.STRIPE_SECRET_KEY);
    await synchronizeStripeSubscription(await stripe.subscriptions.retrieve(subscriptionId));
    return { refreshed: true };
  });

function checkoutReturnOrigin(value, context) {
  const requestedOrigin = stringValue(value).trim().replace(/\/+$/, "");
  const requestOrigin = stringValue(context.rawRequest?.get("origin")).trim().replace(/\/+$/, "");
  if (!requestedOrigin || requestedOrigin !== requestOrigin || !requestedOrigin.startsWith("https://")) {
    throw new functions.https.HttpsError("invalid-argument", "Checkout must return to the dashboard that started it.");
  }
  return requestedOrigin;
}

function normalizeSubscriptionSelection(value) {
  const selection = value && typeof value === "object" ? value : {};
  const count = (key) => {
    const amount = selection[key];
    if (!Number.isInteger(amount) || amount < 0 || amount > 100) {
      throw new functions.https.HttpsError("invalid-argument", `Invalid ${key} quantity.`);
    }
    return amount;
  };
  const requestedModuleIds = Array.isArray(selection.moduleIds) ? selection.moduleIds : [];
  const moduleIds = [...new Set(requestedModuleIds.filter((id) => typeof id === "string"))];
  if (moduleIds.some((id) => !Object.hasOwn(PAID_MODULE_PRICE_IDS, id))) {
    throw new functions.https.HttpsError("invalid-argument", "An unsupported module was requested.");
  }
  return { peoplePacks: count("peoplePacks"), spacePacks: count("spacePacks"), storage100Packs: count("storage100Packs"), storage500Packs: count("storage500Packs"), moduleIds };
}

function checkoutLineItems(selection) {
  const lineItems = [{ price: STRIPE_PRICE_IDS.foundation, quantity: 1 }];
  if (selection.peoplePacks) lineItems.push({ price: STRIPE_PRICE_IDS.people250, quantity: selection.peoplePacks });
  if (selection.spacePacks) lineItems.push({ price: STRIPE_PRICE_IDS.spaces10, quantity: selection.spacePacks });
  if (selection.storage100Packs) lineItems.push({ price: STRIPE_PRICE_IDS.storage100, quantity: selection.storage100Packs });
  if (selection.storage500Packs) lineItems.push({ price: STRIPE_PRICE_IDS.storage500, quantity: selection.storage500Packs });
  selection.moduleIds.forEach((moduleId) => lineItems.push({ price: PAID_MODULE_PRICE_IDS[moduleId], quantity: 1 }));
  return lineItems;
}

function selectionFromSubscription(subscription) {
  const quantities = new Map((subscription.items?.data || []).map((item) => [stringValue(item.price?.id).trim(), Math.max(0, Number(item.quantity) || 0)]));
  return {
    peoplePacks: quantities.get(STRIPE_PRICE_IDS.people250) || 0,
    spacePacks: quantities.get(STRIPE_PRICE_IDS.spaces10) || 0,
    storage100Packs: quantities.get(STRIPE_PRICE_IDS.storage100) || 0,
    storage500Packs: quantities.get(STRIPE_PRICE_IDS.storage500) || 0,
    moduleIds: Object.entries(PAID_MODULE_PRICE_IDS).filter(([, priceId]) => quantities.has(priceId)).map(([moduleId]) => moduleId),
  };
}

function subscriptionUpdateItems(subscription, selection) {
  const desired = new Map(checkoutLineItems(selection).map((item) => [item.price, item.quantity]));
  const items = [];
  for (const item of subscription.items?.data || []) {
    const priceId = stringValue(item.price?.id).trim();
    if (desired.has(priceId)) {
      items.push({ id: item.id, quantity: desired.get(priceId) });
      desired.delete(priceId);
    } else {
      items.push({ id: item.id, deleted: true });
    }
  }
  for (const [price, quantity] of desired) items.push({ price, quantity });
  return items;
}

function subscriptionPeriodEnd(subscription) {
  const seconds = Number(subscription.current_period_end || subscription.items?.data?.[0]?.current_period_end);
  return Number.isFinite(seconds) ? new Date(seconds * 1000).toISOString() : null;
}

async function subscriptionForOrganization(stripe, organizationId, organization) {
  const knownId = stringValue(organization.billing?.stripeSubscriptionId).trim();
  if (knownId) return stripe.subscriptions.retrieve(knownId);
  const customerId = stringValue(organization.billing?.stripeCustomerId).trim();
  if (!customerId) return null;
  const subscriptions = await stripe.subscriptions.list({ customer: customerId, status: "all", limit: 10 });
  return subscriptions.data.find((subscription) =>
    (subscription.status === "active" || subscription.status === "trialing") && stringValue(subscription.metadata?.organizationId).trim() === organizationId,
  ) || null;
}

exports.stripeWebhook = functions
  .runWith({ secrets: ["STRIPE_SECRET_KEY", "STRIPE_WEBHOOK_SECRET"] })
  .https.onRequest(async (request, response) => {
    if (request.method !== "POST") {
      response.status(405).send("Method not allowed");
      return;
    }
    const stripeSecretKey = process.env.STRIPE_SECRET_KEY;
    const webhookSecret = process.env.STRIPE_WEBHOOK_SECRET;
    const signature = request.header("stripe-signature");
    if (!stripeSecretKey || !webhookSecret || !signature) {
      response.status(400).send("Webhook configuration is incomplete");
      return;
    }
    const stripe = new Stripe(stripeSecretKey);
    let event;
    try {
      event = stripe.webhooks.constructEvent(request.rawBody, signature, webhookSecret);
    } catch (error) {
      logger.warn("Stripe webhook signature verification failed", { errorMessage: error instanceof Error ? error.message : String(error) });
      response.status(400).send("Invalid signature");
      return;
    }
    try {
      await processStripeEvent(stripe, event);
      response.status(200).json({ received: true });
    } catch (error) {
      logger.error("Stripe webhook processing failed", { eventId: event.id, eventType: event.type, errorMessage: error instanceof Error ? error.message : String(error) });
      response.status(500).send("Webhook processing failed");
    }
  });

async function processStripeEvent(stripe, event) {
  switch (event.type) {
    case "checkout.session.completed": {
      const session = event.data.object;
      const organizationId = stringValue(session.client_reference_id || session.metadata?.organizationId).trim();
      if (organizationId) {
        await admin.firestore().collection("organizations").doc(organizationId).set({
          billing: {
            stripeCustomerId: stringValue(session.customer).trim() || null,
            checkoutSessionId: session.id,
            checkoutCompletedAt: admin.firestore.FieldValue.serverTimestamp(),
          },
        }, { merge: true });
      }
      return;
    }
    case "customer.subscription.created":
    case "customer.subscription.updated":
      await synchronizeStripeSubscription(event.data.object);
      return;
    case "customer.subscription.deleted":
      await revertSubscriptionToFreePlan(event.data.object, "cancelled");
      return;
    case "invoice.paid": {
      const subscriptionId = stringValue(event.data.object.subscription).trim();
      if (subscriptionId) await synchronizeStripeSubscription(await stripe.subscriptions.retrieve(subscriptionId));
      return;
    }
    case "invoice.payment_failed":
      await processFailedInvoice(stripe, event.data.object);
      return;
    default:
      return;
  }
}

async function processFailedInvoice(stripe, invoice) {
  const subscriptionId = stringValue(invoice.subscription).trim();
  if (!subscriptionId) return;
  const subscription = await stripe.subscriptions.retrieve(subscriptionId);
  const organizationId = stringValue(subscription.metadata?.organizationId).trim();
  if (!organizationId) return;
  const attemptCount = Number.isInteger(invoice.attempt_count) ? invoice.attempt_count : 1;
  if (attemptCount >= 3) {
    const cancelledSubscription = await stripe.subscriptions.cancel(subscription.id);
    await revertSubscriptionToFreePlan(cancelledSubscription, "payment_failed");
    return;
  }
  await admin.firestore().collection("organizations").doc(organizationId).set({
    billing: {
      stripeSubscriptionId: subscription.id,
      status: "past_due",
      paymentFailureCount: attemptCount,
      reminderCount: attemptCount,
      lastPaymentFailureAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
  }, { merge: true });
  await sendBillingReminder(organizationId, attemptCount);
}

async function sendBillingReminder(organizationId, attemptCount) {
  const administrators = await admin.firestore().collection("organizations").doc(organizationId).collection("members").where("role", "==", "primary_admin").get();
  await Promise.all(administrators.docs.map((administrator) => {
    const reference = admin.firestore().collection("notifications").doc();
    return reference.set({
      id: reference.id,
      recipientId: administrator.id,
      actorId: "billing",
      type: "billingPaymentReminder",
      targetType: "organizationBilling",
      targetId: organizationId,
      title: `Payment reminder ${attemptCount} of 2`,
      subtitle: "We could not process your organization subscription payment. Update your payment method before the next attempt.",
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      read: false,
      delivered: false,
    });
  }));
}

async function synchronizeStripeSubscription(subscription) {
  const organizationId = stringValue(subscription.metadata?.organizationId).trim();
  if (!organizationId) return;
  if (subscription.status === "canceled" || subscription.status === "incomplete_expired") {
    await revertSubscriptionToFreePlan(subscription, subscription.status);
    return;
  }
  if (subscription.status !== "active" && subscription.status !== "trialing") {
    await admin.firestore().collection("organizations").doc(organizationId).set({
      billing: { stripeSubscriptionId: subscription.id, status: subscription.status, updatedAt: admin.firestore.FieldValue.serverTimestamp() },
    }, { merge: true });
    return;
  }
  const entitlement = subscriptionEntitlements(subscription);
  if (!entitlement) return;
  const periodEndSeconds = Number(subscription.current_period_end);
  await admin.firestore().collection("organizations").doc(organizationId).set({
    entitlements: entitlement,
    // A paid Stripe subscription is the live source of truth. Remove any
    // development-only override that would otherwise mask it in the apps.
    debugEntitlementOverrides: admin.firestore.FieldValue.delete(),
    billing: {
      stripeCustomerId: stringValue(subscription.customer).trim() || null,
      stripeSubscriptionId: subscription.id,
      status: subscription.status,
      cancelAtPeriodEnd: subscription.cancel_at_period_end === true,
      currentPeriodEnd: Number.isFinite(periodEndSeconds) ? new Date(periodEndSeconds * 1000) : null,
      paymentFailureCount: 0,
      reminderCount: 0,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
  }, { merge: true });
}

async function revertSubscriptionToFreePlan(subscription, reason) {
  const organizationId = stringValue(subscription.metadata?.organizationId).trim();
  if (!organizationId) return;
  await admin.firestore().collection("organizations").doc(organizationId).set({
    entitlements: FREE_ORGANIZATION_ENTITLEMENTS,
    billing: {
      stripeCustomerId: stringValue(subscription.customer).trim() || null,
      stripeSubscriptionId: subscription.id,
      status: reason,
      paymentFailureCount: reason === "payment_failed" ? 3 : 0,
      reminderCount: reason === "payment_failed" ? 2 : 0,
      revertedToFreeAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    },
  }, { merge: true });
}

function subscriptionEntitlements(subscription) {
  const quantitiesByPrice = new Map();
  for (const item of subscription.items?.data || []) {
    const priceId = stringValue(item.price?.id).trim();
    if (priceId) quantitiesByPrice.set(priceId, (quantitiesByPrice.get(priceId) || 0) + Math.max(0, Number(item.quantity) || 0));
  }
  if (!quantitiesByPrice.has(STRIPE_PRICE_IDS.foundation)) return null;
  const modules = new Set(FOUNDATION_MODULE_IDS);
  if (quantitiesByPrice.has(STRIPE_PRICE_IDS.communication)) {
    modules.add("announcements");
    modules.add("rooms");
  }
  if (quantitiesByPrice.has(STRIPE_PRICE_IDS.agenda)) {
    modules.add("lists");
    modules.add("notes");
  }
  if (quantitiesByPrice.has(STRIPE_PRICE_IDS.content)) {
    modules.add("photos");
    modules.add("files");
  }
  return {
    peopleCapacity: 250 + (quantitiesByPrice.get(STRIPE_PRICE_IDS.people250) || 0) * 250,
    activeSpaceCapacity: 10 + (quantitiesByPrice.get(STRIPE_PRICE_IDS.spaces10) || 0) * 10,
    enabledModuleIds: [...modules].sort(),
    mediaStorageCapacityBytes: (10 + (quantitiesByPrice.get(STRIPE_PRICE_IDS.storage100) || 0) * 100 + (quantitiesByPrice.get(STRIPE_PRICE_IDS.storage500) || 0) * 500) * 1024 ** 3,
  };
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
