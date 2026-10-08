-- CHAIWALA is renamed BREWBERRY (Brewberry Beverages Ltd.): the old name carried connotations a demo
-- shouldn't have. The market keeps its place in the list, so its prices are unchanged; every service that
-- stores the symbol renames it in the same release.
--
-- Only the symbol is renamed. Instruction ids stay as they were issued: they are the clearing corporation's
-- idempotency keys (movements refer to them), and an instruction sent again under a different id would move
-- shares twice. (Released in 0.2.2 renaming the ids too, which the movements' foreign key refused on real data;
-- Postgres rolled that back wherever it ran, so no database has it applied.)

UPDATE holdings SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE movements SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE transfers SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
