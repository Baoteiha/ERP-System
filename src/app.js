const express = require('express');
require('dotenv').config();

const authRoutes = require('./routes/authRoutes');
const errorHandler = require('./middleware/errorHandler');

const app = express();

app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// Required for reading refresh token cookies
const cookieParser = require('cookie-parser');
app.use(cookieParser());

// Routes
app.use('/api/auth', authRoutes);

// 404
app.use((_req, res) => {
    res.status(404).json({ success: false, message: 'Route not found' });
});

// Global error handler
app.use(errorHandler);

module.exports = app;
