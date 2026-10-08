-- CHAIWALA is renamed BREWBERRY (Brewberry Beverages Ltd.): the old name carried connotations a demo
-- shouldn't have. The market keeps its place in the list, so its prices are unchanged; every service that
-- stores the symbol renames it in the same release.
--
-- Only the symbol is renamed. Instruction ids stay as they were issued: they are the clearing corporation's
-- idempotency keys (movements refer to them), and an instruction sent again under a different id would move
-- shares twice. An account that already holds BREWBERRY (settled after the exchange renamed it, while this
-- service hadn't yet) gets its CHAIWALA shares added to that holding; movements keep the running balance they
-- were recorded with.
--
-- (0.2.2 renamed instruction ids too, which the movements' foreign key refused; 0.2.3 didn't merge holdings and
-- hit the duplicate. Both were rolled back by Postgres wherever they ran, so no database has either applied.)

UPDATE holdings b SET quantity = b.quantity + c.quantity
FROM holdings c
WHERE c.bo_id = b.bo_id AND c.symbol = 'CHAIWALA' AND b.symbol = 'BREWBERRY';

DELETE FROM holdings c
WHERE c.symbol = 'CHAIWALA' AND EXISTS (SELECT 1 FROM holdings b WHERE b.bo_id = c.bo_id AND b.symbol = 'BREWBERRY');

UPDATE holdings SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE movements SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE transfers SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
