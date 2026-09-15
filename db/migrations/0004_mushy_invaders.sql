CREATE TABLE `_draw_seq` (
	`id` integer PRIMARY KEY NOT NULL,
	`v` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE TABLE `drawings` (
	`id` text PRIMARY KEY NOT NULL,
	`strokes` text DEFAULT '[]' NOT NULL,
	`updatedAt` integer DEFAULT 0 NOT NULL,
	`deleted` integer DEFAULT 0 NOT NULL,
	`seq` integer DEFAULT 0 NOT NULL
);
