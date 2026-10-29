-- Lock a dedicated child row, not the dataset parent referenced by uploads and tasks.
CREATE TABLE platform_dataset_write_guard (
  dataset_id BIGINT NOT NULL PRIMARY KEY,
  FOREIGN KEY (dataset_id) REFERENCES platform_dataset(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO platform_dataset_write_guard(dataset_id)
SELECT id FROM platform_dataset;
