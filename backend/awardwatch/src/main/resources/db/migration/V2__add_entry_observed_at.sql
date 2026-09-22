ALTER TABLE availability_entry
    ADD COLUMN observed_at TIMESTAMPTZ;

UPDATE availability_entry AS entry
SET observed_at = snapshot.observed_at
FROM snapshot
WHERE snapshot.id = entry.snapshot_id;

ALTER TABLE availability_entry
    ALTER COLUMN observed_at SET NOT NULL;
