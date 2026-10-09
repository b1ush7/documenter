CREATE TABLE `user` (
                        `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
                        `phone` VARCHAR(50) NULL DEFAULT NULL COLLATE 'utf8mb4_uca1400_ai_ci',
                        `username` VARCHAR(50) NOT NULL COLLATE 'utf8mb4_uca1400_ai_ci',
                        `password` VARCHAR(100) NOT NULL COLLATE 'utf8mb4_uca1400_ai_ci',
                        `role` VARCHAR(16) NOT NULL DEFAULT 'USER' COLLATE 'utf8mb4_uca1400_ai_ci',
                        `balance` DECIMAL(20,6) NOT NULL DEFAULT '5.000000',
                        PRIMARY KEY (`id`) USING BTREE,
                        UNIQUE INDEX `索引 2` (`phone`) USING BTREE,
                        CONSTRAINT `ck_user_role` CHECK (`role` in ('USER','ADMIN'))
)
    COLLATE='utf8mb4_uca1400_ai_ci'
    ENGINE=InnoDB
;
