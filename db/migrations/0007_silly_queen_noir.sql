ALTER TABLE `notes` ADD `checklist` text;--> statement-breakpoint
ALTER TABLE `notes` ADD `attachments` text DEFAULT '[]' NOT NULL;