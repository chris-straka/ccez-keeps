CREATE TABLE `notes` (
	`id` text PRIMARY KEY NOT NULL,
	`title` text DEFAULT '' NOT NULL,
	`body` text DEFAULT '' NOT NULL,
	`color` text DEFAULT 'default' NOT NULL,
	`pinned` integer DEFAULT 0 NOT NULL,
	`archived` integer DEFAULT 0 NOT NULL,
	`updatedAt` integer DEFAULT 0 NOT NULL,
	`deleted` integer DEFAULT 0 NOT NULL
);
