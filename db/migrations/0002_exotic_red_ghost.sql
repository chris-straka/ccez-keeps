CREATE TABLE `device_tokens` (
	`id` text PRIMARY KEY NOT NULL,
	`tokenHash` text NOT NULL,
	`deviceName` text DEFAULT '' NOT NULL,
	`createdAt` integer DEFAULT 0 NOT NULL,
	`lastSeenAt` integer DEFAULT 0 NOT NULL,
	`revoked` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `device_tokens_tokenHash_unique` ON `device_tokens` (`tokenHash`);