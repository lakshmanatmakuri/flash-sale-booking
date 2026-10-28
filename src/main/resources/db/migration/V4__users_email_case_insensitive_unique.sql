-- "Ana@x.com" and "ana@x.com" must be the same account.
-- The application lowercases emails, but the DB is the final line of defense.

CREATE UNIQUE INDEX uq_users_email_lower
    ON users (lower(email));