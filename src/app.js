const express = require('express');
require('dotenv').config();

const authRoutes = require('./routes/auth.routes');
const productRoutes = require('./routes/product.routes');
const productTypeRoutes = require('./routes/product-type.routes');
const errorHandler = require('./middleware/error-handler.middleware');

const app = express();

app.use(express.urlencoded({ extended: false }));
app.use(express.json());

// Required for reading refresh token cookies
const cookieParser = require('cookie-parser');
app.use(cookieParser());

// Routes
app.use('/api/auth', authRoutes);
app.use('/api/products', productRoutes);
app.use('/api/product-types', productTypeRoutes);

// 404
app.use((_req, res) => {
    res.status(404).json({ success: false, message: 'Route not found' });
});

// Global error handler
app.use(errorHandler);

module.exports = app;
