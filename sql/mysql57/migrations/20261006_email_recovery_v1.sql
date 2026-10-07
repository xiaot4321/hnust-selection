-- Compatible with MySQL 5.7/8.0. Apply once after backing up the target database.
USE hnust_selection;
CREATE TABLE account_email (
  account_id BIGINT NOT NULL PRIMARY KEY,
  email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  verified_at DATETIME(3) NOT NULL,
  UNIQUE KEY uq_account_email (email),
  CONSTRAINT fk_account_email_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE email_verification (
  id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  account_version BIGINT NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  used_at DATETIME(3) NULL,
  KEY ix_email_verification_account_created (account_id, created_at),
  CONSTRAINT fk_email_verification_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE email_security_event (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  action_code VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  CONSTRAINT fk_email_security_event_account FOREIGN KEY (account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
