CREATE TABLE "libertybans_revision" (
  "constant" CHARACTER VARYING(8) NOT NULL UNIQUE CHECK ("constant" = 'Constant'),
  "major" INT NOT NULL,
  "minor" INT NOT NULL
);
INSERT INTO "libertybans_revision" VALUES ('Constant', 0, 0);
CREATE TABLE "libertybans_names" (
  "uuid" UUID NOT NULL,
  "name" CHARACTER VARYING(16) NOT NULL,
  "lower_name" CHARACTER VARYING(16) GENERATED ALWAYS AS (LOWER("name")),
  "updated" BIGINT NOT NULL,
  CONSTRAINT "libertybans_uuid_name_uniqueness" UNIQUE ("uuid", "name")
);
CREATE INDEX "libertybans_name_index" ON "libertybans_names" ("lower_name");
CREATE TABLE "libertybans_addresses" (
  "uuid" UUID NOT NULL,
  "address" VARBINARY(16) NOT NULL,
  "updated" BIGINT NOT NULL,
CONSTRAINT "libertybans_uuid_address_uniqueness" UNIQUE ("uuid", "address")
);
CREATE INDEX "libertybans_address_index" ON "libertybans_addresses" ("address");
CREATE TABLE "libertybans_punishments" (
  "id" BIGINT NOT NULL,
  "type" SMALLINT NOT NULL,
  "operator" UUID NOT NULL,
  "reason" CHARACTER VARYING(256) NOT NULL,
  "scope" CHARACTER VARYING(32) NOT NULL,
  "start" BIGINT NOT NULL,
  "end" BIGINT NOT NULL,
  CONSTRAINT "libertybans_punishment_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_punishment_type_validity" CHECK ("type" >= 0 AND "type" <= 3)
);
CREATE INDEX "libertybans_punishment_operator_index" ON "libertybans_punishments" ("operator");
CREATE INDEX "libertybans_punishment_start_index" ON "libertybans_punishments" ("start");
CREATE INDEX "libertybans_punishment_end_index" ON "libertybans_punishments" ("end");
CREATE TABLE "libertybans_victims" (
  "id" INT NOT NULL,
  "type" SMALLINT NOT NULL,
  "uuid" UUID NOT NULL,
  "address" VARBINARY(16) NOT NULL,
  CONSTRAINT "libertybans_victim_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_victim_type_validity" CHECK ("type" >= 0 AND "type" <= 2),
  CONSTRAINT "libertybans_victim_uniqueness" UNIQUE ("type", "uuid", "address")
);
CREATE TABLE "libertybans_bans" (
  "id" BIGINT NOT NULL,
  "victim" INT NOT NULL,
  CONSTRAINT "libertybans_ban_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_ban_id_validity" FOREIGN KEY ("id") REFERENCES "libertybans_punishments" ("id") ON DELETE CASCADE,
  CONSTRAINT "libertybans_ban_victim_uniqueness" UNIQUE ("victim"),
  CONSTRAINT "libertybans_ban_victim_validity" FOREIGN KEY ("victim") REFERENCES "libertybans_victims" ("id")
);
CREATE TABLE "libertybans_mutes" (
  "id" BIGINT NOT NULL,
  "victim" INT NOT NULL,
  CONSTRAINT "libertybans_mute_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_mute_id_validity" FOREIGN KEY ("id") REFERENCES "libertybans_punishments" ("id") ON DELETE CASCADE,
  CONSTRAINT "libertybans_mute_victim_uniqueness" UNIQUE ("victim"),
  CONSTRAINT "libertybans_mute_victim_validity" FOREIGN KEY ("victim") REFERENCES "libertybans_victims" ("id")
);
CREATE TABLE "libertybans_warns" (
  "id" BIGINT NOT NULL,
  "victim" INT NOT NULL,
  CONSTRAINT "libertybans_warn_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_warn_id_validity" FOREIGN KEY ("id") REFERENCES "libertybans_punishments" ("id") ON DELETE CASCADE,
  CONSTRAINT "libertybans_warn_victim_validity" FOREIGN KEY ("victim") REFERENCES "libertybans_victims" ("id")
);
CREATE INDEX "libertybans_warn_victim_index" ON "libertybans_warns" ("victim");
CREATE TABLE "libertybans_history" (
  "id" BIGINT NOT NULL,
  "victim" INT NOT NULL,
  CONSTRAINT "libertybans_history_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_history_id_validity" FOREIGN KEY ("id") REFERENCES "libertybans_punishments" ("id") ON DELETE CASCADE,
  CONSTRAINT "libertybans_history_victim_validity" FOREIGN KEY ("victim") REFERENCES "libertybans_victims" ("id")
);
CREATE INDEX "libertybans_history_victim_index" ON "libertybans_history" ("victim");
CREATE TABLE "libertybans_messages" (
  "message" BLOB NOT NULL,
  "time" BIGINT NOT NULL
);
CREATE INDEX "libertybans_messages_time_index" ON "libertybans_messages" ("time");
ALTER TABLE "libertybans_addresses"
  ADD CONSTRAINT "libertybans_address_length"
  CHECK (OCTET_LENGTH("address") IN (4, 16));
ALTER TABLE "libertybans_victims"
  ADD CONSTRAINT "libertybans_victim_address_length"
  CHECK (OCTET_LENGTH("address") IN (4, 16));
ALTER TABLE "libertybans_punishments"
  ADD CONSTRAINT "libertybans_punishment_duration_positivity"
  CHECK ("end" = 0 OR "end" > "start");
CREATE TABLE "libertybans_tracks" (
  "id" INT NOT NULL,
  "namespace" CHARACTER VARYING(64) NOT NULL,
  "value" CHARACTER VARYING(64) NOT NULL,
  CONSTRAINT "libertybans_track_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_track_uniqueness" UNIQUE ("namespace", "value"),
  CONSTRAINT "libertybans_track_validity" CHECK ("namespace" <> '' AND "value" <> '' AND "namespace" NOT LIKE '%:%' AND "value" NOT LIKE '%:%')
);
ALTER TABLE "libertybans_punishments" ADD COLUMN "track" INT NULL;
CREATE TABLE "libertybans_scopes" (
  "id" INT NOT NULL,
  "type" SMALLINT NOT NULL,
  "value" CHARACTER VARYING(32) NOT NULL,
  CONSTRAINT "libertybans_scope_id_uniqueness" UNIQUE ("id"),
  CONSTRAINT "libertybans_scope_type_validity" CHECK ("type" IN (1, 2)),
  CONSTRAINT "libertybans_scope_data_uniqueness" UNIQUE ("type", "value")
);
ALTER TABLE "libertybans_punishments" ADD COLUMN "scope_id" INT NULL;
CREATE INDEX "libertybans_victim_uuid_index" ON "libertybans_victims" ("uuid");
CREATE INDEX "libertybans_victim_address_index" ON "libertybans_victims" ("address");
