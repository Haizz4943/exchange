-- Tạo các database cho từng service
-- Script này chạy tự động khi PostgreSQL container khởi động lần đầu

CREATE DATABASE auth_db;
CREATE DATABASE wallet_db;
CREATE DATABASE order_db;
CREATE DATABASE match_db;
