"""Add upi_account_transactions table and copy data from beauty_transactions

Revision ID: 20260925_add_upi_account_transactions
Revises: <previous_revision_id>
Create Date: 2026-09-25 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa

# revision identifiers, used by Alembic.
revision = '20260925_add_upi_account_transactions'
down_revision = '<previous_revision_id>'
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        'upi_account_transactions',
        sa.Column('id', sa.Integer(), primary_key=True),
        sa.Column('amount', sa.Numeric(precision=20, scale=6), nullable=False),
        sa.Column('type', sa.String(), nullable=False),
        sa.Column('note', sa.String(), nullable=True),
        sa.Column('timestamp', sa.BigInteger(), nullable=False),
        sa.Column('previous_balance', sa.Numeric(precision=20, scale=6), nullable=False),
        sa.Column('transaction_amount', sa.Numeric(precision=20, scale=6), nullable=False),
        sa.Column('new_balance', sa.Numeric(precision=20, scale=6), nullable=False),
        sa.Column('sync_id', sa.String(), nullable=False, index=False),
        sa.Column('updated_at', sa.BigInteger(), nullable=False),
        sa.Column('deleted_at', sa.BigInteger(), nullable=True),
        sa.Column('sync_status', sa.String(), nullable=False)
    )

    # Copy data from beauty_transactions preserving ids and sync metadata
    conn = op.get_bind()
    conn.execute(sa.text(
        "INSERT INTO upi_account_transactions (id, amount, type, note, timestamp, previous_balance, transaction_amount, new_balance, sync_id, updated_at, deleted_at, sync_status) "
        "SELECT id, amount, type, note, timestamp, previous_balance, transaction_amount, new_balance, sync_id, updated_at, deleted_at, sync_status FROM beauty_transactions"
    ))


def downgrade():
    op.drop_table('upi_account_transactions')
