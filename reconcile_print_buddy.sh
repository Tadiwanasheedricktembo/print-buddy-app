#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.tadiwaprintbuddy.app"
APP_DB_PATH="/data/data/${PACKAGE}/databases/print_database"
SDCARD="/sdcard"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_DB_NAME="${BACKUP_DB_NAME:-print_buddy_pre_recon_${TIMESTAMP}.db}"
WORK_DB_NAME="${WORK_DB_NAME:-print_buddy_recon_work_${TIMESTAMP}.db}"
LOCAL_OUTPUT_DIR="${1:-./print_buddy_recon_${TIMESTAMP}}"

CSV_DRIFT="drift_report.csv"
CSV_ORPHANS="orphaned_settlements.csv"
CSV_MISCLASSIFIED="misclassified_orders.csv"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

print_usage() {
  cat <<EOF
Usage:
  ./reconcile_print_buddy.sh
  ./reconcile_print_buddy.sh /path/to/output_folder

Prerequisites:
  - adb installed and available on PATH
  - one Android device/emulator connected
  - the app package is installed: ${PACKAGE}

Precondition:
  Run this backup command on the device first:

  adb shell "run-as ${PACKAGE} cp ${APP_DB_PATH} ${SDCARD}/${BACKUP_DB_NAME}"

The script will continue only if the backup file exists at ${SDCARD}/${BACKUP_DB_NAME}.
EOF
}

check_adb_device() {
  adb get-state >/dev/null 2>&1 || fail "No Android device/emulator found. Connect a device and try again."
}

ensure_app_installed() {
  adb shell "pm path ${PACKAGE}" >/dev/null 2>&1 || fail "App package not found: ${PACKAGE}"
}

print_precondition() {
  echo
  echo "Precondition:"
  echo "Run this one-line backup command on the device first:"
  echo
  echo "adb shell \"run-as ${PACKAGE} cp ${APP_DB_PATH} ${SDCARD}/${BACKUP_DB_NAME}\""
  echo
  echo "The script will continue only if that backup file exists at ${SDCARD}/${BACKUP_DB_NAME}."
  echo
}

copy_backup_to_work_db() {
  echo "[1/5] Copying backup DB to working copy..."
  adb shell "test -f ${SDCARD}/${BACKUP_DB_NAME}" >/dev/null 2>&1 || fail "Backup file not found: ${SDCARD}/${BACKUP_DB_NAME}"
  adb shell "rm -f ${SDCARD}/${WORK_DB_NAME}" >/dev/null 2>&1 || true
  adb shell "cp ${SDCARD}/${BACKUP_DB_NAME} ${SDCARD}/${WORK_DB_NAME}" >/dev/null 2>&1 || fail "Failed to copy backup DB to ${SDCARD}/${WORK_DB_NAME}"
  adb shell "chmod 666 ${SDCARD}/${WORK_DB_NAME}" >/dev/null 2>&1 || true
}

run_sql_query_to_csv() {
  local out_file="$1"
  local sql="$2"

  echo "[2/5] Exporting ${out_file}..."
  adb shell "rm -f ${SDCARD}/${out_file}" >/dev/null 2>&1 || true
  adb shell "sqlite3 -csv -header ${SDCARD}/${WORK_DB_NAME} \"${sql}\" > ${SDCARD}/${out_file}" >/dev/null 2>&1 \
    || fail "Failed to run SQL query for ${out_file}"
}

pull_outputs() {
  echo "[3/5] Pulling CSV files to local folder..."
  mkdir -p "${LOCAL_OUTPUT_DIR}"

  adb pull "${SDCARD}/${CSV_DRIFT}" "${LOCAL_OUTPUT_DIR}/${CSV_DRIFT}" >/dev/null 2>&1 || fail "Failed to pull ${CSV_DRIFT}"
  adb pull "${SDCARD}/${CSV_ORPHANS}" "${LOCAL_OUTPUT_DIR}/${CSV_ORPHANS}" >/dev/null 2>&1 || fail "Failed to pull ${CSV_ORPHANS}"
  adb pull "${SDCARD}/${CSV_MISCLASSIFIED}" "${LOCAL_OUTPUT_DIR}/${CSV_MISCLASSIFIED}" >/dev/null 2>&1 || fail "Failed to pull ${CSV_MISCLASSIFIED}"
}

count_data_rows() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo 0
    return
  fi

  tail -n +2 "$file" 2>/dev/null | wc -l | tr -d '[:space:]'
}

print_summary() {
  echo "[4/5] Summary of extracted CSVs:"
  echo

  local files=(
    "${LOCAL_OUTPUT_DIR}/${CSV_DRIFT}"
    "${LOCAL_OUTPUT_DIR}/${CSV_ORPHANS}"
    "${LOCAL_OUTPUT_DIR}/${CSV_MISCLASSIFIED}"
  )

  local labels=(
    "drift_report.csv"
    "orphaned_settlements.csv"
    "misclassified_orders.csv"
  )

  local any_rows=0
  local idx=0

  for file in "${files[@]}"; do
    local rows
    rows="$(count_data_rows "$file")"
    if (( rows > 0 )); then
      any_rows=1
    fi

    echo " - ${labels[$idx]}: ${rows} row(s)"
    if (( rows > 0 )); then
      echo "   Top 10 rows:"
      sed -n '1,11p' "$file"
      echo
    else
      echo "   No rows found."
      echo
    fi
    idx=$((idx + 1))
  done

  echo "Output directory: ${LOCAL_OUTPUT_DIR}"
  echo

  if (( any_rows > 0 )); then
    echo "Result: reconciliation issues were found."
    echo "The script will exit with status 1."
    return 1
  fi

  echo "Result: no reconciliation issues found in the exported CSVs."
  echo "The script will exit with status 0."
  return 0
}

print_remediation_sql() {
  cat <<'EOF'
Remediation SQL templates:

1) Recompute debtor_credits for one customer

```sql
-- Replace 123 with the actual customerId
BEGIN;

DELETE FROM debtor_credits
WHERE customerId = 123;

INSERT INTO debtor_credits (
    customerId,
    customerName,
    amount,
    lastUpdated
)
SELECT
    c.id,
    c.displayName,
    COALESCE(
        (
            SELECT sh.newBalance
            FROM settlement_history sh
            WHERE sh.customerId = c.id
            ORDER BY sh.timestamp DESC, sh.id DESC
            LIMIT 1
        ),
        (
            SELECT IFNULL(SUM(o.totalAmount - o.paidAmount), 0.0)
            FROM orders o
            WHERE o.customerId = c.id
              AND o.orderStatus = 'ACTIVE'
        )
    ) AS computed_balance,
    strftime('%s','now') * 1000;
FROM customers c
WHERE c.id = 123;

COMMIT;
```

2) Insert a compensating ledger entry for a ghost order reference

```sql
-- Replace placeholders with real values
INSERT INTO settlement_history (
    customerName,
    previousBalance,
    settledAmount,
    remainingBalance,
    timestamp,
    type,
    note,
    customerId,
    transactionAmount,
    newBalance,
    originId,
    ledgerEntryType,
    isShadowDuplicate,
    reconciliationStatus
) VALUES (
    'RECONCILIATION',
    0.0,
    0.0,
    0.0,
    strftime('%s','now') * 1000,
    'ADJUSTMENT',
    'Compensating entry for ghost order reference #<originId>',
    <customerId>,
    0.0,
    0.0,
    <originId>,
    'ADJUSTMENT',
    1,
    'FLAGGED'
);
```

3) Mark an order as soft-deleted and create a reversal entry

```sql
-- Replace 42 with the real order id
BEGIN;

UPDATE orders
SET orderStatus = 'CANCELLED'
WHERE id = 42;

INSERT INTO settlement_history (
    customerName,
    previousBalance,
    settledAmount,
    remainingBalance,
    timestamp,
    type,
    note,
    customerId,
    transactionAmount,
    newBalance,
    originId,
    ledgerEntryType,
    isShadowDuplicate,
    reconciliationStatus
)
SELECT
    o.customerName,
    o.previousBalance,
    0.0,
    o.newBalance - (o.totalAmount - o.paidAmount),
    strftime('%s','now') * 1000,
    'CANCEL',
    'Reversal for cancelled order #42',
    o.customerId,
    -(o.totalAmount - o.paidAmount),
    o.newBalance - (o.totalAmount - o.paidAmount),
    o.id,
    'ORDER_CANCEL',
    0,
    'VERIFIED'
FROM orders o
WHERE o.id = 42;

COMMIT;
```
EOF
}

main() {
  case "${1:-}" in
    -h|--help)
      print_usage
      exit 0
      ;;
  esac

  require_cmd adb
  require_cmd sqlite3

  echo "== Print Buddy reconciliation export =="
  echo "Package: ${PACKAGE}"
  echo "Local output folder: ${LOCAL_OUTPUT_DIR}"
  echo

  check_adb_device
  ensure_app_installed
  print_precondition

  if ! adb shell "test -f ${SDCARD}/${BACKUP_DB_NAME}" >/dev/null 2>&1; then
    echo "Backup file not found yet: ${SDCARD}/${BACKUP_DB_NAME}"
    echo "Please run the backup command above, then re-run this script."
    exit 2
  fi

  copy_backup_to_work_db

  local drift_sql
  drift_sql="SELECT dc.customerId, dc.customerName, dc.amount AS debtor_credit_amount, COALESCE(SUM(sh.transactionAmount), 0) AS settlement_total, (dc.amount - COALESCE(SUM(sh.transactionAmount), 0)) AS drift FROM debtor_credits dc LEFT JOIN settlement_history sh ON sh.customerId = dc.customerId GROUP BY dc.customerId, dc.customerName, dc.amount HAVING dc.amount != COALESCE(SUM(sh.transactionAmount), 0);"

  local orphan_sql
  orphan_sql="SELECT id, customerId, customerName, originId, ledgerEntryType, timestamp FROM settlement_history WHERE ledgerEntryType = 'ORDER_POST' AND originId NOT IN (SELECT id FROM orders);"

  local mis_sql
  mis_sql="SELECT id, customerId, customerName, totalAmount, paidAmount, paymentMethod, orderStatus FROM orders WHERE paymentMethod = 'CASH' AND paidAmount = 0 AND totalAmount > 0;"

  run_sql_query_to_csv "${CSV_DRIFT}" "${drift_sql}"
  run_sql_query_to_csv "${CSV_ORPHANS}" "${orphan_sql}"
  run_sql_query_to_csv "${CSV_MISCLASSIFIED}" "${mis_sql}"

  pull_outputs

  if print_summary; then
    print_remediation_sql
    exit 0
  fi

  print_remediation_sql
  exit 1
}

main "$@"
