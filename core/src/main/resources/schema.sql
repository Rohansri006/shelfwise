-- =====================================================================
-- ShelfWise database schema
-- =====================================================================
-- Target: SQLite 3 (the application). Written in deliberately portable SQL
-- so it can be reused for a MySQL assignment with only type substitutions:
--
--   SQLite          ->  MySQL
--   TEXT            ->  VARCHAR(n) / DATE
--   REAL            ->  DECIMAL(10,2)
--   INTEGER (0/1)   ->  TINYINT(1)
--
-- No AUTOINCREMENT is used anywhere: every primary key is a UUID generated
-- in Java, because the application model already gives each object an id and
-- objects must keep that id across export and re-import.
--
-- IMPORTANT: SQLite does not enforce foreign keys unless each connection
-- runs "PRAGMA foreign_keys = ON". Without it every ON DELETE rule below is
-- silently ignored. The application does this in one openConnection() helper.
-- =====================================================================

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- Trees: locations and categories
-- Both are the same shape - a node that optionally points at its parent.
-- This self-referencing foreign key is how a tree of unlimited depth is
-- stored in a flat table, and it replaces the recursive object graph the
-- Java model holds in memory.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS location (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL CHECK (length(trim(name)) > 0),
    -- RESTRICT: deleting a location that still has children fails. The
    -- application deletes a subtree leaf-first, so a mistake cannot orphan.
    parent_id  TEXT REFERENCES location(id) ON DELETE RESTRICT,
    -- Two children of the same parent may not share a name. Enforces the
    -- "unique among siblings" rule in the database, not just in Java.
    UNIQUE (parent_id, name)
);

CREATE TABLE IF NOT EXISTS category (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL CHECK (length(trim(name)) > 0),
    parent_id  TEXT REFERENCES category(id) ON DELETE RESTRICT,
    UNIQUE (parent_id, name)
);

-- The UNIQUE constraints above do NOT cover root nodes.
--
-- In SQL, NULL is never equal to NULL, so a UNIQUE constraint containing a
-- NULL column never matches: two root categories both named "Non-fiction"
-- would both be accepted. These partial indexes close that hole by applying
-- uniqueness only to the rows where parent_id IS NULL.
--
-- Portability note: partial indexes are supported by SQLite and PostgreSQL
-- but NOT by MySQL. A MySQL port must either store roots with a sentinel
-- parent value instead of NULL, or enforce this rule in Java.
CREATE UNIQUE INDEX IF NOT EXISTS idx_category_root_name
    ON category (name) WHERE parent_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_location_root_name
    ON location (name) WHERE parent_id IS NULL;

-- ---------------------------------------------------------------------
-- Series
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS series (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0),
    -- 0 means "unknown", matching the model's convention for optional numbers.
    total_books  INTEGER NOT NULL DEFAULT 0 CHECK (total_books >= 0)
);

-- ---------------------------------------------------------------------
-- Items
--
-- The Java model has an abstract CollectionItem with Book as its only
-- subclass today. That inheritance is represented here as class-table
-- inheritance: shared columns live in "item", book-specific columns live in
-- "book", and book.item_id is both its primary key and a foreign key back
-- to the parent row.
--
-- Chosen over one wide table because a future item type (a comic, a vinyl
-- record) becomes a new table rather than a column that is NULL for every
-- existing row.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS item (
    id          TEXT PRIMARY KEY,
    -- Discriminator: tells the loader which subclass to build. 'BOOK' in v1.
    item_type   TEXT NOT NULL CHECK (item_type IN ('BOOK')),
    title       TEXT NOT NULL CHECK (length(trim(title)) > 0),
    notes       TEXT,
    -- 0 = unrated. Mirrors the validating setter in Java.
    rating      INTEGER NOT NULL DEFAULT 0 CHECK (rating BETWEEN 0 AND 5),
    date_added  TEXT NOT NULL,
    -- Soft delete: the trash. 0 = live, 1 = in trash.
    deleted     INTEGER NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
);

CREATE TABLE IF NOT EXISTS book (
    -- Shared primary key with item: this is what makes it the same object.
    item_id           TEXT PRIMARY KEY REFERENCES item(id) ON DELETE CASCADE,
    subtitle          TEXT,
    -- Nullable because books without barcodes exist, but unique when present.
    -- Both SQLite and MySQL permit many NULLs in a UNIQUE column.
    isbn              TEXT UNIQUE,
    publisher         TEXT,
    publication_year  INTEGER NOT NULL DEFAULT 0
                      CHECK (publication_year = 0 OR publication_year BETWEEN 1450 AND 2200),
    language          TEXT,
    pages             INTEGER NOT NULL DEFAULT 0 CHECK (pages >= 0),
    -- SET NULL: deleting a series leaves its books, un-serialised.
    series_id         TEXT REFERENCES series(id) ON DELETE SET NULL,
    series_position   INTEGER NOT NULL DEFAULT 0 CHECK (series_position >= 0),
    reading_status    TEXT NOT NULL DEFAULT 'UNREAD'
                      CHECK (reading_status IN ('UNREAD', 'READING', 'FINISHED', 'ABANDONED'))
);

-- ---------------------------------------------------------------------
-- Copies: the physical objects on the shelf
--
-- Composition in the Java model (a Copy cannot exist without its Book), so
-- CASCADE here: deleting the book deletes its copies.
--
-- The location foreign key is RESTRICT, which is what enforces
-- LocationNotEmptyException - the database itself refuses to delete a
-- location while a copy still sits in it.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS copy (
    id             TEXT PRIMARY KEY,
    book_item_id   TEXT NOT NULL REFERENCES book(item_id) ON DELETE CASCADE,
    location_id    TEXT REFERENCES location(id) ON DELETE RESTRICT,
    format         TEXT NOT NULL DEFAULT 'PAPERBACK'
                   CHECK (format IN ('HARDCOVER', 'PAPERBACK', 'EBOOK', 'AUDIOBOOK', 'OTHER')),
    condition      TEXT NOT NULL DEFAULT 'GOOD'
                   CHECK (condition IN ('NEW', 'LIKE_NEW', 'GOOD', 'FAIR', 'POOR')),
    purchase_date  TEXT,
    price_paid     REAL NOT NULL DEFAULT 0 CHECK (price_paid >= 0)
);

-- ---------------------------------------------------------------------
-- Contributors: many-to-many with books
--
-- A person is stored once and linked to every book they worked on, which is
-- what takes this design to third normal form. The junction table is the
-- relational form of the Java model's contributor list.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS contributor (
    id    TEXT PRIMARY KEY,
    name  TEXT NOT NULL CHECK (length(trim(name)) > 0),
    role  TEXT NOT NULL DEFAULT 'AUTHOR'
          CHECK (role IN ('AUTHOR', 'TRANSLATOR', 'EDITOR', 'ILLUSTRATOR')),
    -- The same human in two roles is two rows, which is intentional:
    -- "Gregory Rabassa, translator" is a different credit to "author".
    UNIQUE (name, role)
);

CREATE TABLE IF NOT EXISTS book_contributor (
    book_item_id    TEXT NOT NULL REFERENCES book(item_id) ON DELETE CASCADE,
    contributor_id  TEXT NOT NULL REFERENCES contributor(id) ON DELETE CASCADE,
    -- Order of credit on the book, from 0. Rows in a table have no order
    -- of their own, so without this the primary author (the first AUTHOR)
    -- could change on every reload.
    position        INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    PRIMARY KEY (book_item_id, contributor_id)
);

-- ---------------------------------------------------------------------
-- Item/category membership: many-to-many
--
-- RESTRICT on category_id is what enforces CategoryNotEmptyException: the
-- database refuses to delete a category while any item is still filed under
-- it. CASCADE on item_id means deleting an item just drops its filings.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS item_category (
    item_id      TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    category_id  TEXT NOT NULL REFERENCES category(id) ON DELETE RESTRICT,
    PRIMARY KEY (item_id, category_id)
);

-- ---------------------------------------------------------------------
-- Reading history
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS reading_session (
    id                TEXT PRIMARY KEY,
    book_item_id      TEXT NOT NULL REFERENCES book(item_id) ON DELETE CASCADE,
    start_date        TEXT NOT NULL,
    -- NULL means the session is still open: currently reading.
    end_date          TEXT,
    pages_read        INTEGER NOT NULL DEFAULT 0 CHECK (pages_read >= 0),
    percent_complete  INTEGER NOT NULL DEFAULT 0
                      CHECK (percent_complete BETWEEN 0 AND 100),
    -- ISO-8601 dates compare correctly as text, so this works without a
    -- real date type - one reason dates are stored as TEXT.
    CHECK (end_date IS NULL OR end_date >= start_date)
);

-- ---------------------------------------------------------------------
-- Wishlists
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS wishlist (
    id    TEXT PRIMARY KEY,
    name  TEXT NOT NULL UNIQUE CHECK (length(trim(name)) > 0)
);

CREATE TABLE IF NOT EXISTS wishlist_entry (
    id              TEXT PRIMARY KEY,
    wishlist_id     TEXT NOT NULL REFERENCES wishlist(id) ON DELETE CASCADE,
    title           TEXT NOT NULL CHECK (length(trim(title)) > 0),
    isbn            TEXT,
    author          TEXT,
    priority        TEXT NOT NULL DEFAULT 'MEDIUM'
                    CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH')),
    expected_price  REAL NOT NULL DEFAULT 0 CHECK (expected_price >= 0),
    store           TEXT NOT NULL DEFAULT 'Amazon',
    rare            INTEGER NOT NULL DEFAULT 0 CHECK (rare IN (0, 1))
);

-- ---------------------------------------------------------------------
-- User-defined fields
--
-- The Java model exposes Map<String,String> customFields. Normalised, that
-- is one row per key instead of a serialised blob, so the values stay
-- queryable.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS custom_field (
    item_id      TEXT NOT NULL REFERENCES item(id) ON DELETE CASCADE,
    -- NOCASE: "Signed" and "signed" are the same field, so the primary key
    -- refuses a second spelling of one key on the same item.
    field_key    TEXT NOT NULL COLLATE NOCASE CHECK (length(trim(field_key)) > 0),
    field_value  TEXT,
    -- Order the user added the fields in, from 0, matching the model's
    -- LinkedHashMap. Without it, fields come back in arbitrary order.
    position     INTEGER NOT NULL DEFAULT 0 CHECK (position >= 0),
    PRIMARY KEY (item_id, field_key)
);

-- ---------------------------------------------------------------------
-- Application settings (single profile per install)
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS app_setting (
    setting_key    TEXT PRIMARY KEY,
    setting_value  TEXT
);

-- ---------------------------------------------------------------------
-- Indexes
-- Primary keys and UNIQUE constraints are indexed automatically; these
-- cover the columns the application filters and joins on most.
-- ---------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_item_deleted        ON item (deleted);
CREATE INDEX IF NOT EXISTS idx_item_title          ON item (title);
CREATE INDEX IF NOT EXISTS idx_book_reading_status ON book (reading_status);
CREATE INDEX IF NOT EXISTS idx_copy_book           ON copy (book_item_id);
CREATE INDEX IF NOT EXISTS idx_copy_location       ON copy (location_id);
CREATE INDEX IF NOT EXISTS idx_itemcat_category    ON item_category (category_id);
CREATE INDEX IF NOT EXISTS idx_bookcontrib_contrib ON book_contributor (contributor_id);
CREATE INDEX IF NOT EXISTS idx_session_book        ON reading_session (book_item_id);
CREATE INDEX IF NOT EXISTS idx_category_parent     ON category (parent_id);
CREATE INDEX IF NOT EXISTS idx_location_parent     ON location (parent_id);

-- ---------------------------------------------------------------------
-- Views
--
-- These exist so the browse and statistics screens read from real
-- multi-table joins rather than assembling everything in Java loops.
-- ---------------------------------------------------------------------

-- One row per live book, with its authors flattened and its copies counted.
CREATE VIEW IF NOT EXISTS v_book_summary AS
SELECT
    i.id                AS item_id,
    i.title             AS title,
    b.subtitle          AS subtitle,
    b.isbn              AS isbn,
    b.publisher         AS publisher,
    b.publication_year  AS publication_year,
    b.pages             AS pages,
    b.reading_status    AS reading_status,
    i.rating            AS rating,
    s.name              AS series_name,
    b.series_position   AS series_position,
    (SELECT COUNT(*) FROM copy c WHERE c.book_item_id = b.item_id) AS copy_count,
    (SELECT group_concat(con.name, ', ')
       FROM book_contributor bc
       JOIN contributor con ON con.id = bc.contributor_id
      WHERE bc.book_item_id = b.item_id
        AND con.role = 'AUTHOR')                                   AS authors
FROM item i
JOIN book b ON b.item_id = i.id
LEFT JOIN series s ON s.id = b.series_id
WHERE i.deleted = 0;

-- Total money spent, and how it splits by format. Backs the statistics screen.
CREATE VIEW IF NOT EXISTS v_collection_value AS
SELECT
    c.format              AS format,
    COUNT(*)              AS copies,
    SUM(c.price_paid)     AS total_paid
FROM copy c
JOIN item i ON i.id = c.book_item_id
WHERE i.deleted = 0
GROUP BY c.format;

-- Books finished per year, from closed reading sessions.
CREATE VIEW IF NOT EXISTS v_books_per_year AS
SELECT
    substr(rs.end_date, 1, 4)        AS finished_year,
    COUNT(DISTINCT rs.book_item_id)  AS books_finished
FROM reading_session rs
JOIN item i ON i.id = rs.book_item_id
WHERE rs.end_date IS NOT NULL
  AND i.deleted = 0
GROUP BY substr(rs.end_date, 1, 4);

-- =====================================================================
-- Reference query: a category and all of its descendants.
--
-- Kept here as documentation rather than a view, because it is
-- parameterised. This recursive common table expression replaces what
-- would otherwise be a tree walk in Java, and is how "filter by a parent
-- category includes everything beneath it" is implemented.
--
--   WITH RECURSIVE subtree(id) AS (
--       SELECT id FROM category WHERE id = ?
--       UNION ALL
--       SELECT c.id FROM category c JOIN subtree s ON c.parent_id = s.id
--   )
--   SELECT DISTINCT i.*
--     FROM item i
--     JOIN item_category ic ON ic.item_id = i.id
--    WHERE ic.category_id IN (SELECT id FROM subtree)
--      AND i.deleted = 0;
--
-- The same shape, with "location" and "copy", answers "everything on this
-- shelf including its rows".
-- =====================================================================
