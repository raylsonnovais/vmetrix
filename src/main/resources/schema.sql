-- ============================================================================
--  VMetrix Query Manager — schema
--  Creates (1) the data-model tables, (2) the metadata tables that describe
--  that model to the engine. Seed data lives in data.sql.
--
--  Everything the engine knows about the model comes from the META_* tables,
--  never from Java. Adding a table/column here + a row in META_* is enough to
--  make the engine query it — no code change.
-- ============================================================================

-- ---------------------------------------------------------------------------
--  1. DATA MODEL
--  Oracle-style types (VARCHAR2 / NUMBER / DATE / TIMESTAMP) resolved by H2's
--  Oracle compatibility mode. Insert order for the seed is PARTY -> INSTRUMENT
--  -> TRANSACTION so the foreign keys below are always satisfiable.
-- ---------------------------------------------------------------------------

CREATE TABLE PARTY (
    PARTY_ID    NUMBER        PRIMARY KEY,
    PARTY_NAME  VARCHAR2(200) NOT NULL,
    PARTY_TYPE  VARCHAR2(30)  NOT NULL,   -- COUNTERPARTY, ISSUER, BROKER, CUSTODIAN
    TAX_ID      VARCHAR2(30),
    COUNTRY     VARCHAR2(3),              -- ISO country code
    SECTOR      VARCHAR2(50),
    RATING      VARCHAR2(10),
    IS_ACTIVE   NUMBER(1)     NOT NULL    -- 1 = active, 0 = inactive
);

CREATE TABLE INSTRUMENT (
    INSTRUMENT_ID   NUMBER        PRIMARY KEY,
    TICKER          VARCHAR2(20),
    INSTRUMENT_NAME VARCHAR2(200) NOT NULL,
    INSTRUMENT_TYPE VARCHAR2(30)  NOT NULL,   -- BOND, EQUITY, FUND, DERIVATIVE, DEPOSIT
    ASSET_CLASS     VARCHAR2(30)  NOT NULL,   -- FIXED_INCOME, EQUITY, ALTERNATIVES
    ISSUER_ID       NUMBER,                   -- FK -> PARTY (issuer)
    CURRENCY        VARCHAR2(3)   NOT NULL,
    MATURITY_DATE   DATE,                      -- null for instruments without maturity
    COUPON_RATE     NUMBER(8,4),               -- coupon (%) for fixed income
    NOMINAL_VALUE   NUMBER(18,2),
    IS_ACTIVE       NUMBER(1)     NOT NULL,
    CONSTRAINT FK_INSTRUMENT_ISSUER FOREIGN KEY (ISSUER_ID) REFERENCES PARTY (PARTY_ID)
);

-- TRANSACTION is a reserved word in several dialects; the logical->physical mapping in
-- the metadata isolates the rest of the engine from that. If the plain identifier fails
-- to create under Oracle mode, this is the single place to quote it.
CREATE TABLE TRANSACTION (
    TXN_ID          NUMBER        PRIMARY KEY,
    TXN_DATE        DATE          NOT NULL,
    TXN_TYPE        VARCHAR2(20)  NOT NULL,   -- BUY, SELL, MATURITY, COUPON, DIVIDEND
    QUANTITY        NUMBER(18,6),
    PRICE           NUMBER(18,8),
    AMOUNT          NUMBER(18,2)  NOT NULL,
    CURRENCY        VARCHAR2(3)   NOT NULL,
    STATUS          VARCHAR2(20)  NOT NULL,   -- PENDING, APPROVED, SETTLED, CANCELLED
    SETTLEMENT_DATE DATE,
    PORTFOLIO_ID    NUMBER        NOT NULL,
    INSTRUMENT_ID   NUMBER,                   -- FK -> INSTRUMENT
    COUNTERPARTY_ID NUMBER,                   -- FK -> PARTY (counterparty)
    CREATED_AT      TIMESTAMP     NOT NULL,
    CONSTRAINT FK_TXN_INSTRUMENT   FOREIGN KEY (INSTRUMENT_ID)   REFERENCES INSTRUMENT (INSTRUMENT_ID),
    CONSTRAINT FK_TXN_COUNTERPARTY FOREIGN KEY (COUNTERPARTY_ID) REFERENCES PARTY (PARTY_ID)
);

-- ---------------------------------------------------------------------------
--  2. METADATA
--  The engine reads these tables at startup into an immutable in-memory
--  catalog. This is the ONLY description of the model the engine has.
-- ---------------------------------------------------------------------------

-- Logical entity -> physical table, with its default SQL alias.
CREATE TABLE META_ENTITY (
    ENTITY         VARCHAR2(50)  PRIMARY KEY,   -- logical name, e.g. 'transaction'
    PHYSICAL_TABLE VARCHAR2(100) NOT NULL,      -- physical table, e.g. 'TRANSACTION'
    DEFAULT_ALIAS  VARCHAR2(20)  NOT NULL,      -- default SQL alias, e.g. 't'
    DESCRIPTION    VARCHAR2(400)
);

-- Logical field -> physical column, with data type, key/FK info and access flags.
-- NAME is camelCase (the API vocabulary); PHYSICAL_NAME is SNAKE_CASE (the column).
-- The camelCase -> SNAKE_CASE mapping is a lookup here, never a string transformation.
CREATE TABLE META_FIELD (
    ENTITY        VARCHAR2(50)  NOT NULL,       -- FK -> META_ENTITY.ENTITY
    NAME          VARCHAR2(50)  NOT NULL,       -- logical camelCase name
    PHYSICAL_NAME VARCHAR2(100) NOT NULL,       -- physical SNAKE_CASE column
    DATA_TYPE     VARCHAR2(20)  NOT NULL,       -- string | number | date | timestamp
    PK            NUMBER(1)     DEFAULT 0 NOT NULL,
    FK_ENTITY     VARCHAR2(50),                 -- target entity when this field is a FK
    FK_FIELD      VARCHAR2(50),                 -- target field  when this field is a FK
    FILTERABLE    NUMBER(1)     DEFAULT 1 NOT NULL,
    SELECTABLE    NUMBER(1)     DEFAULT 1 NOT NULL,
    CONSTRAINT PK_META_FIELD PRIMARY KEY (ENTITY, NAME),
    CONSTRAINT FK_META_FIELD_ENTITY FOREIGN KEY (ENTITY) REFERENCES META_ENTITY (ENTITY)
);

-- Named relationships (JOIN edges). ALIAS is the query-facing name of the edge
-- (e.g. 'counterparty', 'issuer'); SOURCE/TARGET fields are logical camelCase names
-- resolved to physical columns via META_FIELD. Two edges (counterparty, issuer) both
-- point to PARTY: modelling the edge (not the table) is what lets PARTY appear twice.
CREATE TABLE META_RELATION (
    ALIAS         VARCHAR2(50)  PRIMARY KEY,    -- e.g. 'instrument', 'counterparty', 'issuer'
    SOURCE_ENTITY VARCHAR2(50)  NOT NULL,       -- FK -> META_ENTITY.ENTITY
    SOURCE_FIELD  VARCHAR2(50)  NOT NULL,       -- logical field on the source entity
    TARGET_ENTITY VARCHAR2(50)  NOT NULL,       -- FK -> META_ENTITY.ENTITY
    TARGET_FIELD  VARCHAR2(50)  NOT NULL,       -- logical field on the target entity
    JOIN_TYPE     VARCHAR2(20)  NOT NULL,       -- LEFT | INNER | RIGHT | FULL
    CONSTRAINT FK_META_RELATION_SOURCE FOREIGN KEY (SOURCE_ENTITY) REFERENCES META_ENTITY (ENTITY),
    CONSTRAINT FK_META_RELATION_TARGET FOREIGN KEY (TARGET_ENTITY) REFERENCES META_ENTITY (ENTITY)
);

-- The comparator x data-type matrix (spec 5.3). Also metadata: which comparators are
-- valid for which type is data, never a switch in Java.
CREATE TABLE META_COMPARATOR (
    DATA_TYPE  VARCHAR2(20) NOT NULL,           -- string | number | date | timestamp
    COMPARATOR VARCHAR2(30) NOT NULL,           -- equals, notEquals, greaterThan, ...
    CONSTRAINT PK_META_COMPARATOR PRIMARY KEY (DATA_TYPE, COMPARATOR)
);
