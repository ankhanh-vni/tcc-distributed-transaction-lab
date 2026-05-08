#!/bin/bash
set -e

# Create one DB+user per service so each participant is logically isolated.
# This script is mounted at /docker-entrypoint-initdb.d so it runs once on first start.

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE USER inventory   WITH PASSWORD 'inventory';
    CREATE USER payment     WITH PASSWORD 'payment';
    CREATE USER orders      WITH PASSWORD 'orders';
    CREATE USER coordinator WITH PASSWORD 'coordinator';

    CREATE DATABASE inventory_db   OWNER inventory;
    CREATE DATABASE payment_db     OWNER payment;
    CREATE DATABASE order_db       OWNER orders;
    CREATE DATABASE coordinator_db OWNER coordinator;
EOSQL
