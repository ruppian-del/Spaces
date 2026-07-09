const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");

const PROJECT_ID = "spaces-rules-v2";
const SPACE_ID = "space-v2";
const OWNER_ID = "owner-user";
const ADMIN_ID = "admin-user";
const MODERATOR_ID = "moderator-user";
const MEMBER_ID = "member-user";
const GUEST_ID = "guest-user";

const RULES_PATH = path.resolve(__dirname, "../../firestore.rules");
const [host, portText] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8080").split(":");

let testEnv;

function memberDoc(role, displayName) {
  return {
    userId: role === "owner" ? OWNER_ID : `${role}-user`,
    displayName,
    emojiAvatar: "🙂",
    role,
    joinedAt: new Date(),
  };
}

function authedDb(uid) {
  return testEnv.authenticatedContext(uid).firestore();
}

async function seedBaseData() {
  await testEnv.clearFirestore();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const batch = db.batch();
    const spaceRef = db.doc(`spaces/${SPACE_ID}`);
    const now = new Date();

    batch.set(spaceRef, {
      id: SPACE_ID,
      name: "V2 Space",
      emoji: "🏠",
      color: "#4F46E5",
      description: "Shared role test space",
      template: "Custom",
      enabledModules: ["general", "files", "events", "members"],
      ownerId: OWNER_ID,
      memberIds: [OWNER_ID, ADMIN_ID, MODERATOR_ID, MEMBER_ID, GUEST_ID],
      createdAt: now,
      updatedAt: now,
    });

    batch.set(db.doc(`spaces/${SPACE_ID}/members/${OWNER_ID}`), {
      userId: OWNER_ID,
      displayName: "Owner",
      emojiAvatar: "👑",
      role: "owner",
      joinedAt: now,
    });
    batch.set(db.doc(`spaces/${SPACE_ID}/members/${ADMIN_ID}`), {
      userId: ADMIN_ID,
      displayName: "Admin",
      emojiAvatar: "🛠️",
      role: "admin",
      joinedAt: now,
    });
    batch.set(db.doc(`spaces/${SPACE_ID}/members/${MODERATOR_ID}`), {
      userId: MODERATOR_ID,
      displayName: "Moderator",
      emojiAvatar: "🧭",
      role: "moderator",
      joinedAt: now,
    });
    batch.set(db.doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`), {
      userId: MEMBER_ID,
      displayName: "Member",
      emojiAvatar: "🙂",
      role: "member",
      joinedAt: now,
    });
    batch.set(db.doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`), {
      userId: GUEST_ID,
      displayName: "Guest",
      emojiAvatar: "👀",
      role: "guest",
      joinedAt: now,
    });

    batch.set(db.doc(`spaces/${SPACE_ID}/events/event-owner`), {
      id: "event-owner",
      spaceId: SPACE_ID,
      title: "Owner event",
      description: "",
      location: "",
      startDate: now,
      endDate: now,
      allDay: false,
      timezone: "America/Chicago",
      createdBy: OWNER_ID,
      createdByName: "Owner",
      createdAt: now,
      updatedAt: now,
      deleted: false,
    });

    batch.set(db.doc(`spaces/${SPACE_ID}/files/file-owner`), {
      id: "file-owner",
      spaceId: SPACE_ID,
      name: "owner.pdf",
      mimeType: "application/pdf",
      fileExtension: "pdf",
      storagePath: `spaces/${SPACE_ID}/files/file-owner.enc`,
      encryptionVersion: "aes-gcm-v1",
      nonceBase64: "nonce-owner",
      uploadedBy: OWNER_ID,
      uploadedByName: "Owner",
      fileSize: 128,
      deleted: false,
      createdAt: now,
      updatedAt: now,
    });

    batch.set(db.doc(`spaces/${SPACE_ID}/files/file-member`), {
      id: "file-member",
      spaceId: SPACE_ID,
      name: "member.pdf",
      mimeType: "application/pdf",
      fileExtension: "pdf",
      storagePath: `spaces/${SPACE_ID}/files/file-member.enc`,
      encryptionVersion: "aes-gcm-v1",
      nonceBase64: "nonce-member",
      uploadedBy: MEMBER_ID,
      uploadedByName: "Member",
      fileSize: 256,
      deleted: false,
      createdAt: now,
      updatedAt: now,
    });

    await batch.commit();
  });
}

test.before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      host,
      port: Number(portText),
      rules: readFileSync(RULES_PATH, "utf8"),
    },
  });
});

test.after(async () => {
  await testEnv.cleanup();
});

test.beforeEach(async () => {
  await seedBaseData();
});

test("all roles with view_content can read a shared space document", async () => {
  await assertSucceeds(authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}`).get());
  await assertSucceeds(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}`).get());
  await assertSucceeds(authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}`).get());
  await assertSucceeds(authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}`).get());
  await assertSucceeds(authedDb(GUEST_ID).doc(`spaces/${SPACE_ID}`).get());
});

test("invite creation is allowed for owner and admin only", async () => {
  const invitePayload = {
    code: "INVITEA1",
    spaceId: SPACE_ID,
    spaceName: "V2 Space",
    spaceEmoji: "🏠",
    createdBy: OWNER_ID,
    createdAt: new Date(),
    expiresAt: new Date(Date.now() + 86400000),
    maxUses: 25,
    usedCount: 0,
    active: true,
  };

  await assertSucceeds(authedDb(OWNER_ID).doc("spaceInvites/INVITEA1").set(invitePayload));
  await assertSucceeds(
    authedDb(ADMIN_ID).doc("spaceInvites/INVITEA2").set({
      ...invitePayload,
      code: "INVITEA2",
      createdBy: ADMIN_ID,
    })
  );
  await assertFails(
    authedDb(MODERATOR_ID).doc("spaceInvites/INVITEA3").set({
      ...invitePayload,
      code: "INVITEA3",
      createdBy: MODERATOR_ID,
    })
  );
  await assertFails(
    authedDb(MEMBER_ID).doc("spaceInvites/INVITEA4").set({
      ...invitePayload,
      code: "INVITEA4",
      createdBy: MEMBER_ID,
    })
  );
  await assertFails(
    authedDb(GUEST_ID).doc("spaceInvites/INVITEA5").set({
      ...invitePayload,
      code: "INVITEA5",
      createdBy: GUEST_ID,
    })
  );
});

test("member removal is allowed for owner and admin only", async () => {
  await assertSucceeds(authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`).delete());
  await seedBaseData();
  await assertSucceeds(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`).delete());
  await seedBaseData();
  await assertFails(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${OWNER_ID}`).delete());
  await assertFails(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${ADMIN_ID}`).delete());
  await assertFails(authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`).delete());
  await assertFails(authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`).delete());
  await assertFails(authedDb(GUEST_ID).doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`).delete());
});

test("role changes follow owner and admin transition limits", async () => {
  await assertSucceeds(
    authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`).update({ role: "guest" })
  );
  await seedBaseData();
  await assertSucceeds(
    authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`).update({ role: "guest" })
  );
  await assertSucceeds(
    authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${GUEST_ID}`).update({ role: "moderator" })
  );
  await assertFails(
    authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`).update({ role: "admin" })
  );
  await assertFails(
    authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/members/${OWNER_ID}`).update({ role: "member" })
  );
  await assertFails(
    authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}/members/${MEMBER_ID}`).update({ role: "guest" })
  );
});

test("event creation is allowed for owner, admin, moderator, and member but not guest", async () => {
  const now = new Date();
  const payloadFor = (uid, id) => ({
    id,
    spaceId: SPACE_ID,
    title: `${uid} event`,
    description: "",
    location: "",
    startDate: now,
    endDate: now,
    allDay: false,
    timezone: "America/Chicago",
    createdBy: uid,
    createdByName: uid,
    createdAt: now,
    updatedAt: now,
    deleted: false,
  });

  await assertSucceeds(authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}/events/event-owner-create`).set(payloadFor(OWNER_ID, "event-owner-create")));
  await assertSucceeds(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/events/event-admin-create`).set(payloadFor(ADMIN_ID, "event-admin-create")));
  await assertSucceeds(authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}/events/event-moderator-create`).set(payloadFor(MODERATOR_ID, "event-moderator-create")));
  await assertSucceeds(authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}/events/event-member-create`).set(payloadFor(MEMBER_ID, "event-member-create")));
  await assertFails(authedDb(GUEST_ID).doc(`spaces/${SPACE_ID}/events/event-guest-create`).set(payloadFor(GUEST_ID, "event-guest-create")));
});

test("file upload is allowed for owner, admin, moderator, and member but not guest", async () => {
  const now = new Date();
  const payloadFor = (uid, id) => ({
    id,
    spaceId: SPACE_ID,
    name: `${id}.pdf`,
    mimeType: "application/pdf",
    fileExtension: "pdf",
    storagePath: `spaces/${SPACE_ID}/files/${id}.enc`,
    encryptionVersion: "aes-gcm-v1",
    nonceBase64: `${id}-nonce`,
    uploadedBy: uid,
    uploadedByName: uid,
    fileSize: 42,
    deleted: false,
    createdAt: now,
    updatedAt: now,
  });

  await assertSucceeds(authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}/files/file-owner-create`).set(payloadFor(OWNER_ID, "file-owner-create")));
  await assertSucceeds(authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/files/file-admin-create`).set(payloadFor(ADMIN_ID, "file-admin-create")));
  await assertSucceeds(authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}/files/file-moderator-create`).set(payloadFor(MODERATOR_ID, "file-moderator-create")));
  await assertSucceeds(authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}/files/file-member-create`).set(payloadFor(MEMBER_ID, "file-member-create")));
  await assertFails(authedDb(GUEST_ID).doc(`spaces/${SPACE_ID}/files/file-guest-create`).set(payloadFor(GUEST_ID, "file-guest-create")));
});

test("delete own content succeeds for regular members", async () => {
  await assertSucceeds(
    authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}/files/file-member`).update({
      deleted: true,
      deletedBy: MEMBER_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
});

test("delete others content is allowed for owner, admin, and moderator only", async () => {
  await assertSucceeds(
    authedDb(OWNER_ID).doc(`spaces/${SPACE_ID}/files/file-member`).update({
      deleted: true,
      deletedBy: OWNER_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await seedBaseData();
  await assertSucceeds(
    authedDb(ADMIN_ID).doc(`spaces/${SPACE_ID}/files/file-member`).update({
      deleted: true,
      deletedBy: ADMIN_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await seedBaseData();
  await assertSucceeds(
    authedDb(MODERATOR_ID).doc(`spaces/${SPACE_ID}/files/file-owner`).update({
      deleted: true,
      deletedBy: MODERATOR_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await seedBaseData();
  await assertFails(
    authedDb(MEMBER_ID).doc(`spaces/${SPACE_ID}/files/file-owner`).update({
      deleted: true,
      deletedBy: MEMBER_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
  await assertFails(
    authedDb(GUEST_ID).doc(`spaces/${SPACE_ID}/files/file-owner`).update({
      deleted: true,
      deletedBy: GUEST_ID,
      deletedAt: new Date(),
      updatedAt: new Date(),
    })
  );
});
