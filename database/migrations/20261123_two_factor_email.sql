-- CloudHub: two-step verification codes by email
--
-- The same change `php database/migrate.php` makes, for applying by hand -- in
-- phpMyAdmin or Adminer, say, on a host without a PHP command line. Select the
-- CloudHub database (cloud_file_hub unless .env says otherwise) first, and run
-- 20261009_two_factor.sql before this if two-step verification is not set up.
--
-- Safe to run more than once: a column already there is left alone. Nothing
-- is dropped or rewritten. Plain statements only -- no DELIMITER or stored
-- procedure, and no ADD COLUMN IF NOT EXISTS, which MySQL lacks -- so it runs
-- on MySQL and MariaDB alike, and in a web SQL tab as well as the mysql client.

-- On users: the email address codes go to, for an account that chose email
-- over text messages. Nullable and appended, so no row is rewritten.
SET @db := DATABASE();

SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='users' AND COLUMN_NAME='two_factor_email');
SET @sql := IF(@has=0, 'ALTER TABLE users ADD COLUMN two_factor_email VARCHAR(254) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
