-- CHAIWALA is renamed BREWBERRY (Brewberry Beverages Ltd.): the old name carried connotations a demo
-- shouldn't have. The market keeps its place in the list, so its prices are unchanged; every service that
-- stores the symbol renames it in the same release.

UPDATE holdings SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE movements SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE transfers SET symbol = 'BREWBERRY' WHERE symbol = 'CHAIWALA';
UPDATE movements SET instruction_id = replace(instruction_id, 'CHAIWALA', 'BREWBERRY') WHERE instruction_id LIKE '%CHAIWALA%';
UPDATE transfers SET instruction_id = replace(instruction_id, 'CHAIWALA', 'BREWBERRY') WHERE instruction_id LIKE '%CHAIWALA%';
