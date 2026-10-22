-- The platform became domain-neutral: the narrative layer (manuscript drafts, story structure templates) was removed.
DROP TABLE IF EXISTS unit_text_version;
DROP TABLE IF EXISTS custom_template;

-- Graph spaces were renamed: world -> knowledge, characters -> actors (story -> knowledge, templates -> analysis).
UPDATE context_def
SET tabs = (
        SELECT jsonb_agg(DISTINCT_TAB ORDER BY first_pos)
        FROM (
            SELECT CASE t WHEN 'world' THEN 'knowledge' WHEN 'story' THEN 'knowledge'
                          WHEN 'characters' THEN 'actors' WHEN 'templates' THEN 'analysis' ELSE t END AS DISTINCT_TAB,
                   min(pos) AS first_pos
            FROM jsonb_array_elements_text(tabs) WITH ORDINALITY AS x(t, pos)
            GROUP BY 1
        ) mapped
    )
WHERE tabs IS NOT NULL AND jsonb_typeof(tabs) = 'array';

UPDATE context_def
SET space_labels = (
        SELECT jsonb_object_agg(k, v)
        FROM (
            SELECT DISTINCT ON (k) k, v
            FROM (
                SELECT CASE key WHEN 'world' THEN 'knowledge' WHEN 'story' THEN 'knowledge'
                                WHEN 'characters' THEN 'actors' WHEN 'templates' THEN 'analysis' ELSE key END AS k,
                       value AS v,
                       CASE WHEN key IN ('knowledge', 'actors', 'analysis') THEN 0 ELSE 1 END AS prio
                FROM jsonb_each(space_labels)
            ) e
            ORDER BY k, prio
        ) d
    )
WHERE space_labels IS NOT NULL AND jsonb_typeof(space_labels) = 'object' AND space_labels <> '{}'::jsonb;
