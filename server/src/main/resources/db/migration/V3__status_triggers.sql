-- Database-level status-machine guards, mirroring app/modules/content/service.py.
--
-- PostgreSQL used PL/pgSQL functions with RAISE. MySQL trigger bodies with
-- BEGIN/END need a client delimiter, which Flyway does not provide, so each
-- guard is expressed as one statement: an illegal transition writes NULL into
-- a NOT NULL column and MySQL rejects the row. A no-op status update and every
-- legal jump pass through untouched.

DROP TRIGGER IF EXISTS trg_dramas_status_transition;
CREATE TRIGGER trg_dramas_status_transition
BEFORE UPDATE ON dramas FOR EACH ROW
SET NEW.status = IF(
    NEW.status = OLD.status OR (
        (OLD.status = 'draft'      AND NEW.status = 'in_review')
     OR (OLD.status = 'in_review'  AND NEW.status IN ('draft', 'published'))
     OR (OLD.status = 'published'  AND NEW.status = 'unpublished')
     OR (OLD.status = 'unpublished' AND NEW.status IN ('in_review', 'published'))
    ),
    NEW.status,
    NULL
);

DROP TRIGGER IF EXISTS trg_episodes_status_transition;
CREATE TRIGGER trg_episodes_status_transition
BEFORE UPDATE ON episodes FOR EACH ROW
SET NEW.status = IF(
    NEW.status = OLD.status OR (
        (OLD.status = 'not_uploaded'   AND NEW.status = 'transcoding')
     OR (OLD.status = 'transcoding'    AND NEW.status IN ('in_review', 'transcode_failed'))
     OR (OLD.status = 'transcode_failed' AND NEW.status = 'transcoding')
     OR (OLD.status = 'in_review'      AND NEW.status IN ('published', 'not_uploaded'))
     OR (OLD.status = 'published'      AND NEW.status IN ('unpublished', 'not_uploaded'))
     OR (OLD.status = 'unpublished'    AND NEW.status IN ('published', 'not_uploaded'))
    ),
    NEW.status,
    NULL
);
