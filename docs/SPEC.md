# ShelfWise — Frozen Specification (v1)

**Status:** proposed, awaiting one-pass approval. Once approved this document, not `CLAUDE.md`, is authoritative for the data model, interfaces, persistence and screens.

**Why this exists:** the planning audit found the field-level model, every method signature, the whole persistence format and all user-facing text were never discussed, plus 12 contradictions. Every blank below is filled with a concrete default so Tier 1 code can be written without further questions. This file is also the source for the UML class diagram and the requirements document, so code and docs cannot drift.

Conventions used throughout:

- **Optional numbers use `0` to mean "not recorded."** One rule for `rating`, `pages`, `pricePaid`, `publicationYear`, `pagesRead`, `percentComplete`. No `Integer`/`null` boxing, no `Optional`, no NullPointerExceptions — and one sentence to defend in the viva.
- Optional text is `null` or empty. Optional dates are `null`.
- All fields `private`. All mutable fields get validating setters. Collections are returned as unmodifiable copies so callers cannot mutate internals behind the owner's back.

---

## 1. Decisions resolved (contradictions closed)

| # | Contradiction in CLAUDE.md | Resolution |
|---|---|---|
| 1 | Solo vs teammate | Teammate exists, **presents only, writes no code**. Named in the proposal; gets a briefing document in Phase 6. |
| 2 | `Syncable` v1 or v2 | **Defined only.** `SqliteStorage` does *not* implement it. It exists to show the v2 seam and earns interface marks without implementation risk. |
| 3 | Multi-profile | **One profile per install** stands (the later answer supersedes the earlier "multi-user" one). "Multi-profile" is struck from the cut order entirely. |
| 4 | `format`/`purchaseDate` on Book *and* Copy | **Clean split.** Bibliographic data on `Book`, physical data on `Copy`. See §3. |
| 5 | ISBN "core" yet optional | ISBN is **optional**; the internal `id` is the identity. "Core" meant "commonly filled", not "required". |
| 6 | "~5 screens" vs feature list | **Six screens**, listed in §8. Statistics is a seventh only if Phase 5 finishes early. |
| 7 | Two export mechanisms | `Exportable` is **dropped**. Export goes through the abstract `Exporter` hierarchy only. Five interfaces remain — ample for the marks. |
| 8 | "JSON per profile" with one profile | One profile, so **one database file**. Storage is now SQLite — see §6. |
| 9 | Demo dataset vs gitignored `data/` | Demo dataset ships as a **classpath resource** in `desktop/src/main/resources/com/shelfwise/desktop/demo/demo-collection.json`, never in `data/`. |
| 10 | Tier 3 omits multi-profile | Moot — see #3. |
| 11 | "Categorise by author" | Satisfied by contributor search (§5 `Searchable`), not by Categories. |
| 12 | Search omits ISBN | Search **includes ISBN**. |

Also decided without asking, because any defensible choice works provided it can be explained:

- **Password hashing:** `MessageDigest` SHA-256 over a per-install random salt. No dependency, ~10 lines, fully explainable. Not production-grade (no PBKDF2/bcrypt) — stated honestly in the report as a deliberate scope decision.
- **`ReadingStatus`** is a stored field on `Book`, updated when a session is added or closed (closing goes through `Book.finishReadingSession`, D13; removing a session never reverts it, D36). Not derived on read — a stored field is easier to defend than a derivation rule.
- **Contributors are embedded** in `Book` (composition), not shared by ID. The same author appearing on two books is duplicated. Accepted: it keeps the model simple, and `Category` already supplies the aggregation relationship the UML needs.

---

## 2. Exception hierarchy

A base class gives a **second inheritance hierarchy** for the inheritance/abstraction marks, on top of `CollectionItem`.

```
Exception
└── ShelfWiseException (abstract)          checked
    ├── InvalidISBNException
    ├── DuplicateItemException             carries the existing item's id
    ├── CategoryNotEmptyException          carries the blocking book count
    ├── LocationNotEmptyException          carries the blocking book count
    ├── MetadataFetchException             carries the provider name
    ├── SyncConflictException              carries both timestamps
    └── ScannerException                   carries the device name (D34)
```

Checked, so the compiler forces the UI to handle them — which is exactly the "exception handling" the marking scheme wants to see. Standard exceptions still appear: `IOException` and `NumberFormatException` in storage and CSV, `IllegalArgumentException` from validating setters, `try`-with-resources on every stream.

Each exception carries structured data, not just a message, so the UI can build its own wording:

```java
public class CategoryNotEmptyException extends ShelfWiseException {
    private final String categoryName;
    private final int bookCount;
    // getters; getMessage() composes a default sentence
}
```

---

## 3. Model

### 3.1 `CollectionItem` (abstract)

Holds only what *any* future item type shares — this split is what earns the abstraction marks, so it is deliberately minimal.

| Field | Type | Optional | Validation | Mutable |
|---|---|---|---|---|
| `id` | `String` | no | UUID, assigned in constructor | no |
| `title` | `String` | no | non-blank → `IllegalArgumentException` | yes |
| `notes` | `String` | yes | — | yes |
| `rating` | `int` | 0 = unrated | 0–5 → `IllegalArgumentException` | yes |
| `categoryIds` | `List<String>` | empty allowed | — | via add/remove |
| `customFields` | `Map<String,String>` | empty allowed | — | via put/remove; names ignore case, insertion order kept (D30) |
| `dateAdded` | `LocalDate` | no | set in constructor | no |
| `deleted` | `boolean` | default false | — | yes (soft delete) |

Abstract methods, each overridden by `Book`:

```java
public abstract String getIdentifier();      // ISBN if present, else id
public abstract void validate() throws IllegalArgumentException;
public abstract String getDisplaySummary();  // one line for list views
```

### 3.2 `Book extends CollectionItem`

| Field | Type | Optional | Validation |
|---|---|---|---|
| `subtitle` | `String` | yes | — |
| `isbn` | `String` | **yes** | if present: ISBN-10/13 checksum → `InvalidISBNException`; stored as ISBN-13 (D29) |
| `contributors` | `List<Contributor>` | empty allowed | — |
| `publisher` | `String` | yes | — |
| `publicationYear` | `int` | 0 = unknown | 0 or 1450–currentYear+1 |
| `language` | `String` | yes | — |
| `pages` | `int` | 0 = unknown | ≥ 0 |
| `seriesId` | `String` | yes | — |
| `seriesPosition` | `int` | 0 = not in series | ≥ 0 |
| `readingStatus` | `ReadingStatus` | default `UNREAD` | non-null |
| `copies` | `List<Copy>` | **≥ 1 always** | composition; owned |
| `readingSessions` | `List<ReadingSession>` | empty allowed | composition; owned |

**Constructors — overloaded and chained** (the "Constructors" marks):

```java
public Book(String title)                                   // minimum viable
public Book(String isbn, String title)                      // this(title) + isbn
public Book(String isbn, String title, Contributor author)   // chains again
public Book(WishlistEntry entry)                            // promote from wishlist
```

Every constructor funnels to the first via `this(...)`, which calls `super(...)`. Each one creates one default `Copy`, so the "every Book has ≥1 Copy" invariant can never be violated.

### 3.3 `Copy`

Physical instance. Owned by exactly one `Book` (composition).

| Field | Type | Optional | Validation |
|---|---|---|---|
| `id` | `String` | no | UUID |
| `locationId` | `String` | yes (unshelved) | — |
| `format` | `Format` | default `PAPERBACK` | non-null |
| `condition` | `Condition` | default `GOOD` | non-null |
| `purchaseDate` | `LocalDate` | yes | not in the future |
| `pricePaid` | `double` | 0.0 = not recorded | ≥ 0 |

### 3.4 Remaining classes

| Class | Fields |
|---|---|
| `Contributor` | `name` (non-blank), `role` (`ContributorRole`, default `AUTHOR`) |
| `Category` | `id`, `name` (non-blank), `parentId` (null = root) |
| `Location` | `id`, `name` (non-blank), `parentId` (null = root) |
| `Series` | `id`, `name` (non-blank), `totalBooks` (0 = unknown) |
| `ReadingSession` | `id`, `startDate` (required), `endDate` (null = in progress), `pagesRead` (0 = not recorded), `percentComplete` (0–100, 0 = not recorded) |
| `Wishlist` | `id`, `name` (non-blank), `List<WishlistEntry>` (composition) |
| `WishlistEntry` | `id`, `title` (non-blank), `isbn` (optional), `author` (optional), `priority` (`Priority`, default `MEDIUM`), `expectedPrice` (0.0 = unknown), `store` (default `"Amazon"`), `rare` (boolean, manual) |
| `User` (abstract) | `displayName`; abstract `boolean requiresPassword()`, `String getProfileType()` |
| `GuestUser extends User` | no password; `requiresPassword()` → false |
| `LocalUser extends User` | `passwordHash`, `salt`; `requiresPassword()` → true |

`Category` and `Location` hold **`parentId` only — never a parent object reference.** In the database this becomes a self-referencing foreign key (§6.3); in memory the children list stays `transient`.

### 3.5 Enums

Each carries a display label and a getter, demonstrating enums-with-behaviour rather than bare constants.

```java
public enum Condition {
    NEW("New"), LIKE_NEW("Like new"), GOOD("Good"), FAIR("Fair"), POOR("Poor");
    private final String label;
    Condition(String label) { this.label = label; }
    public String getLabel() { return label; }
}
```

| Enum | Constants (label) |
|---|---|
| `ContributorRole` | AUTHOR (Author), TRANSLATOR (Translator), EDITOR (Editor), ILLUSTRATOR (Illustrator) |
| `Format` | HARDCOVER (Hardcover), PAPERBACK (Paperback), EBOOK (E-book), AUDIOBOOK (Audiobook), OTHER (Other) |
| `Condition` | NEW, LIKE_NEW, GOOD, FAIR, POOR (as above) |
| `Priority` | LOW (Low), MEDIUM (Medium), HIGH (High) |
| `ReadingStatus` | UNREAD (Unread), READING (Reading), FINISHED (Finished), ABANDONED (Abandoned) |

---

## 4. Collections used (for the Interfaces & Collections marks)

| Structure | Where | Why |
|---|---|---|
| `HashMap<String, CollectionItem>` | `CollectionManager.itemsById` | O(1) lookup by id; also rebuilds tree links on load |
| `HashMap<String, Category>` | `CollectionManager.categoriesById` | parent resolution after load |
| `ArrayList<Copy>`, `ArrayList<ReadingSession>` | inside `Book` | ordered, owned children |
| `HashSet<String>` | selected-item ids during bulk edit | no duplicates, fast `contains` |
| `TreeMap<String, List<Book>>` | "group by category name" on the browse screen | sorted iteration for free |

---

## 5. Interfaces

Five interfaces. Method signatures are now fixed.

```java
public interface Searchable {
    List<CollectionItem> search(String text);          // title, subtitle, ISBN, contributor, publisher
    List<CollectionItem> search(Category category);    // includes descendants
    List<CollectionItem> search(Location location);    // includes descendants
    List<CollectionItem> search(ReadingStatus status);
}
```

Four overloads of one name — the clearest overloading demonstration in the project.

```java
public interface MetadataProvider {
    String getProviderName();
    BookMetadata fetchByIsbn(String isbn) throws MetadataFetchException;
}

public interface StorageProvider {
    void save(CollectionData data) throws IOException, SyncConflictException;
    CollectionData load() throws IOException;
    boolean dataExists();
}

public interface BarcodeScanner {
    boolean isAvailable();
    void start() throws ScannerException;
    String readNextBarcode() throws ScannerException;     // blocking; null when stopped
    void stop();
}

public interface Syncable {      // DEFINED ONLY — no v1 implementation
    void push() throws IOException;
    void pull() throws IOException;
    LocalDate getLastSyncDate();
}
```

Supporting types: `BookMetadata` (a plain carrier: title, subtitle, authors, publisher, year, pages, language, coverUrl) and `CollectionData` (books, categories, locations, series, wishlists, plus `savedAt`).

**Runtime polymorphism**, called out explicitly for the viva:

```java
// One interface reference, two different objects, two different fetchByIsbn bodies.
List<MetadataProvider> providers = new ArrayList<>();
providers.add(new OpenLibraryProvider());
providers.add(new GoogleBooksProvider());
for (int i = 0; i < providers.size(); i++) {
    MetadataProvider provider = providers.get(i);   // declared type = interface
    try {
        return provider.fetchByIsbn(isbn);          // dispatched at runtime
    } catch (MetadataFetchException e) {
        // fall through to the next provider
    }
}
```

**Lookups, as built** *(added 17 Oct; decisions L1–L15)*. Package `metadata`: an abstract `HttpJsonProvider` (template method: `download` → `parse`, `HttpURLConnection`, 5 s timeouts) with `OpenLibraryProvider` and `GoogleBooksProvider` as subclasses. `MetadataLookup` runs the loop above with two refinements. First, the first source that finds the book gives the base, and the next source only **fills empty fields** (authors, publisher, year, pages, language), never replacing a value. Second, a failing gap-filler is a partial success, not an error. Results are cached per ISBN-13 by `MetadataCache` (JSON files, forever, failures never cached) and returned as a `LookupResult` (details, sources used, sources unavailable). `LookupResult.toBook()` is the user's acceptance. Facts: Open Library answers an unknown ISBN with `{}`, and Google Books **refuses keyless requests with HTTP 429** (shared quota), so the Google key is an optional setting stored in `settings.json`, never in the repository or the database.

The same pattern recurs with `Exporter` (abstract) → `CsvExporter` / `JsonExporter`, with `User` → `GuestUser` / `LocalUser`, and with `CollectionItem` methods invoked through `List<CollectionItem>`.

---

## 6. Persistence — SQLite

Storage is a SQL database rather than JSON files, so the schema can be reused for the RDBMS course project. The full schema lives in **`core/src/main/resources/schema.sql`**, which is the single source of truth: the application executes it on first run, and the same file is submittable to the RDBMS course on its own.

**The marking-scheme trap, and the answer.** The scheme awards 10 marks for *"Exception & File Handling — … persistent **file** storage."* A database alone puts that at risk. So explicit file I/O is retained deliberately: CSV import/export and JSON export written with raw `BufferedReader`/`BufferedWriter` (§6.6), and backup rotation that copies the database file. Both real SQL and real file handling are demonstrable.

### 6.1 Files

| Path | Holds |
|---|---|
| `~/.shelfwise/settings.json` | data-folder path, User type (Guest/Local), display name, password hash + salt, theme, optional Google Books key (D21, L7). Read and written by `SettingsFile` |
| `<data folder>/shelfwise.db` | the entire collection |
| `<data folder>/backups/shelfwise-<yyyyMMdd-HHmmss>.db` | last 5, rotated |
| `<data folder>/cache/covers/<isbn>.jpg` | fetched covers |
| `<data folder>/cache/metadata/<isbn>.json` | fetched metadata |
| classpath `/sample-collection.json` | the bundled sample collection: 25 books, every ISBN confirmed on Open Library (D23), loaded by `SampleCollection` |

Settings sit outside the data folder because they record *where* the data folder is. The fixed `~/.shelfwise` path is computed in the **desktop** module and injected into `core`, honouring the rule that core never hardcodes paths.

### 6.2 Inheritance in the schema

`CollectionItem` → `Book` is mapped as **class-table inheritance**: shared columns in `item`, book-specific columns in `book`, with `book.item_id` serving as both primary key and foreign key back to its parent row. An `item.item_type` discriminator tells the loader which subclass to construct.

Chosen over one wide table because a future item type becomes a *new table* rather than a column that is NULL for every existing row. It costs one join per read and is a direct answer to "how did you represent inheritance in a relational database?"

### 6.3 Trees — the Gson blocker, now gone

`Category` and `Location` each store a self-referencing `parent_id` foreign key. The recursion problem that JSON serialisation created simply does not exist here, and two business rules move into the database:

- `ON DELETE RESTRICT` on `item_category.category_id` **is** `CategoryNotEmptyException` — the database refuses to delete a category while any item is filed under it.
- `ON DELETE RESTRICT` on `copy.location_id` **is** `LocationNotEmptyException`.

The Java layer catches `SQLException`, inspects the constraint, and rethrows the matching `ShelfWiseException` so the UI still receives a typed error.

Querying a subtree uses a recursive common table expression instead of a Java tree walk:

```sql
WITH RECURSIVE subtree(id) AS (
    SELECT id FROM category WHERE id = ?
    UNION ALL
    SELECT c.id FROM category c JOIN subtree s ON c.parent_id = s.id
)
SELECT DISTINCT i.* FROM item i
  JOIN item_category ic ON ic.item_id = i.id
 WHERE ic.category_id IN (SELECT id FROM subtree) AND i.deleted = 0;
```

The in-memory model keeps the `parentId`-plus-`transient`-children design from §3.4 — only its *storage* changed.

### 6.4 Three SQLite behaviours that must not be missed

1. **`PRAGMA foreign_keys = ON` on every connection.** SQLite ignores foreign keys by default, so without this every `ON DELETE` rule above silently does nothing *and the constraint tests would pass for the wrong reason*. It lives in one `openConnection()` helper so no DAO can forget it.
2. **`UNIQUE (parent_id, name)` does not constrain root nodes.** In SQL `NULL ≠ NULL`, so a UNIQUE constraint containing a NULL column never matches — two root categories named "Non-fiction" are both accepted. Closed with partial unique indexes on `(name) WHERE parent_id IS NULL`. *Verified against a live database: duplicate roots and duplicate siblings are both rejected, while the same name under two different parents is still allowed.*
3. **Type affinity is loose**, so the schema's `CHECK` constraints carry real weight: `rating BETWEEN 0 AND 5`, `pages >= 0`, `price_paid >= 0`. They duplicate the Java validating setters on purpose — defence in depth, and a good viva answer about where validation belongs.

Dates are ISO-8601 `TEXT` (`2026-10-12`), which compares and sorts correctly as text and ports to MySQL `DATE` unchanged. Money is `REAL` → MySQL `DECIMAL(10,2)`.

**Order is stored explicitly.** `book_contributor.position` and `custom_field.position` hold the order of credits and custom fields, since table rows have no order of their own; without them the primary author could change on reload. `custom_field.field_key` is `COLLATE NOCASE`, so one key cannot exist under two spellings. *(Added 15 Oct.)*

**Currency.** All prices in a collection share one currency, an ISO 4217 code stored in `app_setting` under the key `currency` (default `INR`). It lives in the database, not `settings.json`, so it travels with the file and its backups. Changing it relabels prices and never converts them. *(Added 15 Oct.)*

### 6.5 Save, backup and conflict detection

**Autosave.** There is no Save button: every logical operation (one edit, one bulk Confirm All) saves at once. *(Decided 15 Oct.)*

Each save **rewrites the whole collection** in one transaction: `setAutoCommit(false)`, delete every row (children before parents; tree tables leaves first, because SQLite applies `RESTRICT` per row), insert the snapshot (parents before children), then `commit()`, with `rollback()` in a `catch` block. A half-applied bulk edit is therefore impossible. Measured: a 1,000-book save takes ~30 ms, so saves run on the UI thread; a test keeps it under 200 ms.

Backups are taken **once per launch**: the first save of a session copies the existing `shelfwise.db` into `backups/shelfwise-<yyyyMMdd-HHmmss>.db` *before* writing, then deletes the oldest while more than 5 exist. With autosave, a backup on every save would cover only the last five edits; once per launch covers the last five sessions. *(Changed 15 Oct from "on each successful save".)*

**Restore** *(added 24 Oct, E46)*: `BackupManager.restore(backup, databaseFile)` first copies the chosen backup beside the database, then backs up the current database (so the restore can itself be undone), then moves the copy over `shelfwise.db` in one step. The screen closes nothing itself (storage opens a connection per operation) and reloads the collection afterwards.

Conflict detection compares the database file's last-modified time against the value recorded at load; if it changed underneath us, `SyncConflictException` carries both timestamps and nothing is written. The UI offers **Keep mine** (overwrite, rebaseline) or **Keep theirs** (discard in-memory state, reload). No merge.

### 6.6 CSV — where the file-handling marks live

Written and parsed with raw `BufferedWriter`/`BufferedReader` (`CsvExporter`, `CsvImporter`, layout in `CsvFormat`):

```
id,title,subtitle,isbn,authors,publisher,year,pages,language,categories,location,format,condition,purchase_date,price_paid (INR),rating,reading_status,notes
```

`id` comes first so a re-import recognises books (ADR 0004); the price header names the currency, and a plain `price_paid` means the collection's own (D18). Multi-valued cells (`authors`, `categories`) are semicolon-separated; categories and locations are full paths joined by ` > `. A field containing a comma, quote or newline is wrapped in double quotes with internal quotes doubled. One row **per copy**, so a two-copy book exports two rows and re-imports as one book with two copies. Live books only, and authors only (D16). On import, columns are found by name, enum cells accept label or name, and per-row errors are collected and reported together rather than aborting the file.

**Import/export, as built** *(added 18 Oct)*. Abstract `Exporter` (template method over a `BufferedWriter`) → `CsvExporter`, `JsonExporter`; abstract `Importer` (over a `BufferedReader`) → `CsvImporter`, `JsonImporter`. JSON holds everything (trash, trees, series, wishlists, history, custom fields, currency) through flat DTOs in `JsonCollection` (D19). Importing is two steps: an importer returns a `ParsedImport` (data, the file's currency, problems) without touching the collection, so the screen can warn about a currency mismatch; then `CollectionMerger` merges (D17): books present by id or ISBN are skipped and listed in an `ImportReport`, with `addAsCopies` on request; trees match by path, series and wishlists by name; merging the same file twice changes nothing. **Known limitation (E1):** a CSV row with neither an id nor an ISBN has nothing that identifies it, so re-importing it adds it again.

### 6.7 Views backing the screens

Defined in `schema.sql` so the UI reads real multi-table joins rather than assembling data in Java loops: `v_book_summary` (one row per live book, authors flattened, copies counted), `v_collection_value` (spend by format), `v_books_per_year` (finished sessions grouped by year, backing the statistics charts).

## 7. Validation rules (consolidated)

| Rule | On violation |
|---|---|
| title non-blank | `IllegalArgumentException` |
| ISBN-10/13 checksum, if ISBN present | `InvalidISBNException` |
| pages ≥ 0, pricePaid ≥ 0, expectedPrice ≥ 0 | `IllegalArgumentException` |
| rating 0–5, percentComplete 0–100 | `IllegalArgumentException` |
| publicationYear 0 or 1450…currentYear+1 | `IllegalArgumentException` |
| purchaseDate not in the future | `IllegalArgumentException` |
| session endDate not before startDate | `IllegalArgumentException` |
| category/location name non-blank, unique among siblings | `IllegalArgumentException` |
| adding a book whose ISBN already exists | `DuplicateItemException` |
| deleting a category with books anywhere in its subtree | `CategoryNotEmptyException` |
| deleting a location with copies anywhere in its subtree | `LocationNotEmptyException` |
| a category/location move that would create a cycle | `IllegalArgumentException` |
| renaming a category/location/series/wishlist to a name taken among its siblings | `IllegalArgumentException` |
| changing a book's ISBN to one another book has | `DuplicateItemException` |
| marking a wishlist entry bought when its book is already owned | `DuplicateItemException`; entry kept |
| adding a wishlist entry whose ISBN (either form) is already on that list *(E127, 24 Oct)* | `IllegalArgumentException`; an import skips it |
| emptying a category/location into a target inside the subtree being emptied *(E101)* | `IllegalArgumentException` |
| restoring a file that is not one of the backups folder's backups *(E46)* | `IllegalArgumentException` |
| saving a collection that breaks any rule above (an edit bypassed the manager) | `IllegalStateException` listing every problem |

**Collection-wide rules live in `CollectionManager`** *(added 18 Oct; D28–D35, ADR 0006)*. Model setters check only their own object, so edits that touch a collection-wide rule go through manager methods: `changeIsbn`, `renameCategory`, `renameLocation`, `renameSeries`, `renameWishlist`, `setLocation`, `fileUnder`, `setSeries`, `markAsBought`, `unfile`, `unshelve`. `toCollectionData()`, where every save starts, runs `checkIntegrity()` first, so the database never sees a broken rule. ISBNs are stored as ISBN-13 and indexed in a `HashMap` for O(1) duplicate checks. Item lists and search results come back sorted by title, ignoring case. Text search also matches custom field values.

*(Added 24 Oct, E101/E115.)* **Deleting a node that still holds books** is two calls: `unfile(category, target)` / `unshelve(location, target)` first moves every filing or copy in the subtree, trashed books included, to `target` (the parent) or drops it when `target` is null; then `deleteCategory`/`deleteLocation` succeeds. Both return how many items/copies changed. **`markAsBought`** starts the new book's first copy at the entry's expected price (0 stays "not recorded"); `Book(WishlistEntry)` itself still carries no price.

ISBN-10 checksum uses weights 10…1 mod 11 with `X` = 10; ISBN-13 uses alternating 1/3 weights mod 10. Both are written out as plain loops.

---

## 8. Screens

Single window, swapped centre panes, left sidebar persistent. Six screens.

| # | Screen | FXML / controller | Contents |
|---|---|---|---|
| 1 | Browse | `browse-view.fxml` / `BrowseController` | grid ⇄ table toggle, search box, category tree, location tree, status filter, multi-select bulk bar |
| 2 | Add / Edit Book | `book-form-view.fxml` / `BookFormController` | all `Book` fields, contributor sub-list, copies sub-list, ISBN lookup button |
| 3 | Bulk Scan | `bulk-scan-view.fxml` / `BulkScanController` | live camera preview or ISBN paste box, review queue, edit/remove per row, Confirm All |
| 4 | Organise | `organise-view.fxml` / `OrganiseController` | two tabs, Categories and Locations, each a `TreeView` with add/rename/delete/move |
| 5 | Wishlist | `wishlist-view.fxml` / `WishlistController` | list selector, entries, Mark as Bought |
| 6 | Trash | `trash-view.fxml` / `TrashController` | soft-deleted items, Restore, Delete Permanently, Empty Trash |

Launch: first run shows a data-folder chooser and the guest-vs-local choice; thereafter Browse. The existing `main-view.fxml` / `MainController` becomes the shell holding the sidebar and the swappable centre.

All FXML hand-written, all handlers `onAction="#method"`, table columns via `PropertyValueFactory`. **Zero lambdas** in the codebase — verified by grepping for `->` before submission.

---

## 9. Tier 1 build order

Each step compiles and tests green before the next begins, so `main` is always gradeable.

1. `exception` — `ShelfWiseException` + six subclasses.
2. `model` — five enums, `CollectionItem`, `Book`, `Copy`, `Contributor`, `Category`, `Location`, `Series`, `ReadingSession`, `Wishlist`, `WishlistEntry`, `User`/`GuestUser`/`LocalUser`.
3. `service` — `Searchable`, `MetadataProvider`, `StorageProvider`, `BarcodeScanner`, `Syncable`, `BookMetadata`, `CollectionData`, `CollectionManager`.
4. `storage` — `SqliteStorage implements StorageProvider` over `BookDao`, `CategoryDao`, `LocationDao`, `WishlistDao` (all `PreparedStatement` + try-with-resources), `schema.sql` bootstrap, database-file backup rotation, abstract `Exporter` → `CsvExporter`/`JsonExporter`, abstract `Importer` → `CsvImporter`/`JsonImporter`, `CollectionMerger`, `SettingsFile`, `SampleCollection`.
5. `metadata` — `OpenLibraryProvider`, `GoogleBooksProvider`, disk cache, demo dataset.

---

## 10. Still unknown — not blocking

| Item | Needed by | Who |
|---|---|---|
| Teammate's name | proposal (Oct 10) | Rohan |
| Submission mechanics; whether proposal/requirements have earlier deadlines | Oct 10 | faculty |
| Whether the evaluator runs the code | affects README effort | faculty |
| Demo dataset contents — real books or curated titles | Oct 17 | Rohan |
| Barcode library choice | Phase 2 spike, today | research, then Rohan approves |

User-facing wording is deliberately **not** frozen here. Nothing was ever specified, and drafting 60 strings up front blocks nothing — each screen's copy is written as that screen is built, then collected into the report.
