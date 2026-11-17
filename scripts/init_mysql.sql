-- MiniTV local MySQL bootstrap (idempotent).
--
-- Run once as a MySQL administrator, then the Java service owns the schema:
--   Flyway creates every table/trigger on first boot.
--
-- Windows (PowerShell, asks for the root password):
--   .\scripts\init_mysql.ps1
--
-- Manual (Linux/macOS):
--   mysql -u root -p < scripts/init_mysql.sql
--
-- The trigger guard below is required because MySQL binaries enable binary
-- logging by default and the app account must create the status-machine
-- triggers during the Flyway migration.

CREATE DATABASE IF NOT EXISTS minitv
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

-- Local development credentials; never reuse them in production.
CREATE USER IF NOT EXISTS 'minitv'@'%' IDENTIFIED BY 'minitv_local';
ALTER USER 'minitv'@'%' IDENTIFIED BY 'minitv_local';

GRANT ALL PRIVILEGES ON minitv.* TO 'minitv'@'%';
FLUSH PRIVILEGES;

SET GLOBAL log_bin_trust_function_creators = 1;

SELECT 'minitv database ready' AS status;
