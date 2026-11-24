ALTER TABLE modl_knowledgebase_articles ADD COLUMN is_starter BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE modl_knowledgebase_articles SET is_starter = TRUE
WHERE slug IN ('server-rules', 'how-to-appeal', 'how-to-report', 'welcome') AND updated_at = created_at;
