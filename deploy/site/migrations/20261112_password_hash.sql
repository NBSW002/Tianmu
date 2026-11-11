-- Run before deploying password-hash writers. No password values are rewritten.
-- Current site schema already uses VARCHAR(255). Older short columns must widen.
DELIMITER $$
DROP PROCEDURE IF EXISTS widen_password_hash_column$$
CREATE PROCEDURE widen_password_hash_column()
BEGIN
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
            AND table_name='user' AND column_name='password' AND character_maximum_length<128) THEN
    ALTER TABLE `user` MODIFY COLUMN password VARCHAR(255) NOT NULL DEFAULT '';
  END IF;
END$$
CALL widen_password_hash_column()$$
DROP PROCEDURE widen_password_hash_column$$
DELIMITER ;
