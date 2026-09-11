-- 005: remove the legacy bootstrap user seeded by 002.
--
-- 002 created a 'legacy-owner' row with the plaintext password '123' so that
-- pre-authentication data had an owner. Authentication now rejects any stored
-- value that is not a PBKDF2 hash, so that row can no longer log in - it is
-- dead weight and a misleading credential sitting in every fresh database.
--
-- Deleting the user cascades to monitored_url (fk_monitored_url_user is
-- ON DELETE CASCADE) and from there to checks and changes, so any data still
-- owned by that placeholder account is removed with it. On a new database
-- there is nothing to remove.

USE url_monitor;

DELETE FROM users WHERE username = 'legacy-owner';
