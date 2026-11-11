-- MySQL 8. Stop all interaction writers and back up the business database first.
-- Default: abort if duplicates exist. Inspect the SELECT output before opting in.
-- SET @interaction_repair_approved = 1;
-- Opt-in keeps the smallest record ID, archives removed records, refunds one coin
-- per removed coin record, and reconciles affected video counters in one transaction.
SELECT 'like' AS kind, user_id, video_id, COUNT(*) AS records
FROM `like` GROUP BY user_id,video_id HAVING COUNT(*)>1
UNION ALL
SELECT 'favorite',user_id,video_id,COUNT(*) FROM favorite GROUP BY user_id,video_id HAVING COUNT(*)>1
UNION ALL
SELECT 'coin',user_id,video_id,COUNT(*) FROM coin GROUP BY user_id,video_id HAVING COUNT(*)>1;

CREATE TABLE IF NOT EXISTS interaction_duplicate_archive (
  kind VARCHAR(16) NOT NULL,
  record_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  video_id BIGINT NOT NULL,
  create_time DATETIME(3),
  archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(kind,record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_interaction_uniqueness$$
CREATE PROCEDURE migrate_interaction_uniqueness()
BEGIN
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    RESIGNAL;
  END;

  DROP TEMPORARY TABLE IF EXISTS interaction_duplicates;
  CREATE TEMPORARY TABLE interaction_duplicates AS
    SELECT 'like' AS kind, a.like_id AS record_id,a.user_id,a.video_id,a.create_time
    FROM `like` a JOIN (SELECT user_id,video_id,MIN(like_id) AS retained FROM `like` GROUP BY user_id,video_id) b
      ON a.user_id=b.user_id AND a.video_id=b.video_id WHERE a.like_id<>b.retained
    UNION ALL
    SELECT 'favorite',a.favorite_id,a.user_id,a.video_id,a.create_time
    FROM favorite a JOIN (SELECT user_id,video_id,MIN(favorite_id) AS retained FROM favorite GROUP BY user_id,video_id) b
      ON a.user_id=b.user_id AND a.video_id=b.video_id WHERE a.favorite_id<>b.retained
    UNION ALL
    SELECT 'coin',a.coin_id,a.user_id,a.video_id,a.create_time
    FROM coin a JOIN (SELECT user_id,video_id,MIN(coin_id) AS retained FROM coin GROUP BY user_id,video_id) b
      ON a.user_id=b.user_id AND a.video_id=b.video_id WHERE a.coin_id<>b.retained;

  IF EXISTS(SELECT 1 FROM interaction_duplicates) AND COALESCE(@interaction_repair_approved,0)<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Review duplicate records and refund policy before approving repair';
  END IF;
  IF EXISTS(SELECT 1 FROM interaction_duplicates d LEFT JOIN video_stats v ON v.video_id=d.video_id WHERE v.video_id IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing video statistics; repair before migration';
  END IF;
  IF EXISTS(SELECT 1 FROM interaction_duplicates d LEFT JOIN user_stats u ON u.user_id=d.user_id WHERE d.kind='coin' AND (u.user_id IS NULL OR u.coin_count IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing coin balance; reconcile before migration';
  END IF;

  START TRANSACTION;
  INSERT INTO interaction_duplicate_archive(kind,record_id,user_id,video_id,create_time)
    SELECT kind,record_id,user_id,video_id,create_time FROM interaction_duplicates;
  UPDATE user_stats u JOIN (SELECT user_id,COUNT(*) AS refund FROM interaction_duplicates WHERE kind='coin' GROUP BY user_id) d
    ON u.user_id=d.user_id SET u.coin_count=u.coin_count+d.refund;
  DELETE a FROM `like` a JOIN interaction_duplicates d ON d.kind='like' AND d.record_id=a.like_id;
  DELETE a FROM favorite a JOIN interaction_duplicates d ON d.kind='favorite' AND d.record_id=a.favorite_id;
  DELETE a FROM coin a JOIN interaction_duplicates d ON d.kind='coin' AND d.record_id=a.coin_id;
  UPDATE video_stats v JOIN (SELECT DISTINCT video_id FROM interaction_duplicates) d ON v.video_id=d.video_id
    SET v.like_count=(SELECT COUNT(*) FROM `like` a WHERE a.video_id=v.video_id),
        v.favorite_count=(SELECT COUNT(*) FROM favorite a WHERE a.video_id=v.video_id),
        v.coin_count=(SELECT COUNT(*) FROM coin a WHERE a.video_id=v.video_id);
  COMMIT;

  -- DDL commits independently. Each guard allows retry after a partial DDL failure.
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='like' AND index_name='uq_like_user_video') THEN
    ALTER TABLE `like` ADD UNIQUE KEY uq_like_user_video(user_id,video_id);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='favorite' AND index_name='uq_favorite_user_video') THEN
    ALTER TABLE favorite ADD UNIQUE KEY uq_favorite_user_video(user_id,video_id);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='coin' AND index_name='uq_coin_user_video') THEN
    ALTER TABLE coin ADD UNIQUE KEY uq_coin_user_video(user_id,video_id);
  END IF;
  DROP TEMPORARY TABLE interaction_duplicates;
END$$
CALL migrate_interaction_uniqueness()$$
DROP PROCEDURE migrate_interaction_uniqueness$$
DELIMITER ;
