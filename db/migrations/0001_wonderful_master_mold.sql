CREATE TABLE `_sync_seq` (
	`id` integer PRIMARY KEY NOT NULL,
	`v` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
ALTER TABLE `notes` ADD `seq` integer DEFAULT 0 NOT NULL;
--> statement-breakpoint
UPDATE `notes` SET `seq` = `rowid`;
--> statement-breakpoint
INSERT INTO `_sync_seq` (`id`, `v`) SELECT 1, COALESCE(MAX(`seq`), 0) FROM `notes`;