CREATE TABLE `_label_seq` (
	`id` integer PRIMARY KEY NOT NULL,
	`v` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE `labels` (
	`id` text PRIMARY KEY NOT NULL,
	`name` text DEFAULT '' NOT NULL,
	`color` text DEFAULT 'default' NOT NULL,
	`updatedAt` integer DEFAULT 0 NOT NULL,
	`deleted` integer DEFAULT 0 NOT NULL,
	`seq` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
ALTER TABLE `notes` ADD `labelIds` text DEFAULT '[]' NOT NULL;--> statement-breakpoint
ALTER TABLE `notes` ADD `reminderAt` integer;