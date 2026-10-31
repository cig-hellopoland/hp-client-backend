BEGIN;
ALTER TABLE sight
  ADD COLUMN IF NOT EXISTS seasonally_closed boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS seasonal_closure_from date,
  ADD COLUMN IF NOT EXISTS seasonal_closure_to date;
ALTER TABLE sightevent
  ADD COLUMN IF NOT EXISTS seasonally_closed boolean NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS seasonal_closure_from date,
  ADD COLUMN IF NOT EXISTS seasonal_closure_to date;
COMMIT;
