CREATE TABLE `enroll_codes` (
	`id` text PRIMARY KEY NOT NULL,
	`codeHash` text NOT NULL,
	`createdAt` integer DEFAULT 0 NOT NULL,
	`used` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `enroll_codes_codeHash_unique` ON `enroll_codes` (`codeHash`);