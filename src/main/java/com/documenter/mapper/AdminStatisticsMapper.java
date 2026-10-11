package com.documenter.mapper;

import com.documenter.vo.AdminOverviewVO;
import com.documenter.vo.AdminUserUsageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 管理员统计查询；只返回聚合数据，不读取文件正文或内部存储标识。 */
@Mapper
public interface AdminStatisticsMapper {

    @Select("""
            SELECT
                (SELECT COUNT(*) FROM `user`) AS totalUsers,
                (SELECT COUNT(*) FROM `user` WHERE status = 'ENABLED') AS enabledUsers,
                (SELECT COUNT(*) FROM `user` WHERE status = 'DISABLED') AS disabledUsers,
                (SELECT COUNT(*) FROM `user` WHERE role = 'ADMIN') AS adminUsers,
                (SELECT COUNT(*) FROM file_asset WHERE status <> 'DELETED') AS activeFiles,
                (SELECT COUNT(*) FROM file_asset WHERE status = 'DELETED') AS deletedFiles,
                (SELECT COALESCE(SUM(size_bytes), 0) FROM file_asset
                    WHERE status <> 'DELETED') AS storageBytes,
                (SELECT COUNT(*) FROM processing_task) AS totalTasks,
                (SELECT COUNT(*) FROM processing_task WHERE status = 'PENDING') AS pendingTasks,
                (SELECT COUNT(*) FROM processing_task WHERE status = 'RUNNING') AS runningTasks,
                (SELECT COUNT(*) FROM processing_task WHERE status = 'SUCCEEDED') AS succeededTasks,
                (SELECT COUNT(*) FROM processing_task WHERE status = 'FAILED') AS failedTasks,
                (SELECT COUNT(*) FROM processing_task WHERE status = 'CANCELLED') AS cancelledTasks,
                CASE
                    WHEN (SELECT COUNT(*) FROM processing_task
                          WHERE status IN ('SUCCEEDED', 'FAILED')) = 0 THEN 0
                    ELSE ROUND(
                        (SELECT COUNT(*) FROM processing_task WHERE status = 'FAILED') /
                        (SELECT COUNT(*) FROM processing_task
                         WHERE status IN ('SUCCEEDED', 'FAILED')),
                        4)
                END AS taskFailureRate
            """)
    AdminOverviewVO selectOverview();

    @Select("""
            SELECT
                u.id AS userId,
                u.username AS username,
                u.role AS role,
                u.status AS status,
                u.balance AS balance,
                COALESCE(f.activeFiles, 0) AS activeFiles,
                COALESCE(f.deletedFiles, 0) AS deletedFiles,
                COALESCE(f.storageBytes, 0) AS storageBytes,
                COALESCE(t.totalTasks, 0) AS totalTasks,
                COALESCE(t.pendingTasks, 0) AS pendingTasks,
                COALESCE(t.runningTasks, 0) AS runningTasks,
                COALESCE(t.succeededTasks, 0) AS succeededTasks,
                COALESCE(t.failedTasks, 0) AS failedTasks,
                COALESCE(t.cancelledTasks, 0) AS cancelledTasks,
                CASE
                    WHEN COALESCE(t.completedTasks, 0) = 0 THEN 0
                    ELSE ROUND(t.failedTasks / t.completedTasks, 4)
                END AS taskFailureRate
            FROM `user` u
            LEFT JOIN (
                SELECT user_id,
                       SUM(CASE WHEN status <> 'DELETED' THEN 1 ELSE 0 END) AS activeFiles,
                       SUM(CASE WHEN status = 'DELETED' THEN 1 ELSE 0 END) AS deletedFiles,
                       COALESCE(SUM(CASE WHEN status <> 'DELETED' THEN size_bytes ELSE 0 END), 0)
                           AS storageBytes
                FROM file_asset
                WHERE user_id = #{userId}
                GROUP BY user_id
            ) f ON f.user_id = u.id
            LEFT JOIN (
                SELECT user_id,
                       COUNT(*) AS totalTasks,
                       SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS pendingTasks,
                       SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END) AS runningTasks,
                       SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeededTasks,
                       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failedTasks,
                       SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelledTasks,
                       SUM(CASE WHEN status IN ('SUCCEEDED', 'FAILED') THEN 1 ELSE 0 END)
                           AS completedTasks
                FROM processing_task
                WHERE user_id = #{userId}
                GROUP BY user_id
            ) t ON t.user_id = u.id
            WHERE u.id = #{userId}
            """)
    AdminUserUsageVO selectUserUsage(@Param("userId") Long userId);
}
