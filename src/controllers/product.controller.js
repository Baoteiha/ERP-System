const productService = require('../services/product.service');

const createProduct = async (req, res, next) => {
    try {
        const { name, sku, category, description, price, unit, stock, lowStockThreshold, expiryTracking, shelfLifeDays, supplier } = req.body;

        if (!name || !sku || !category || price == null || !unit) {
            return res.status(400).json({ success: false, message: 'name, sku, category, price, and unit are required' });
        }

        const product = await productService.createProduct({
            name, sku, category, description, price, unit, stock,
            lowStockThreshold, expiryTracking, shelfLifeDays, supplier,
        });

        res.status(201).json({ success: true, data: product });
    } catch (err) {
        next(err);
    }
};

const getProducts = async (req, res, next) => {
    try {
        const { status, category, lowStock } = req.query;
        const products = await productService.getProducts({ status, category, lowStock });
        res.json({ success: true, data: products });
    } catch (err) {
        next(err);
    }
};

const getProductById = async (req, res, next) => {
    try {
        const product = await productService.getProductById(req.params.id);
        res.json({ success: true, data: product });
    } catch (err) {
        next(err);
    }
};

const updateProduct = async (req, res, next) => {
    try {
        const product = await productService.updateProduct(req.params.id, req.body);
        res.json({ success: true, data: product });
    } catch (err) {
        next(err);
    }
};

const adjustStock = async (req, res, next) => {
    try {
        const { delta } = req.body;
        if (delta == null || typeof delta !== 'number') {
            return res.status(400).json({ success: false, message: 'delta must be a number (positive to add, negative to deduct)' });
        }

        const product = await productService.adjustStock(req.params.id, delta);
        res.json({ success: true, data: product });
    } catch (err) {
        next(err);
    }
};

const deleteProduct = async (req, res, next) => {
    try {
        await productService.deleteProduct(req.params.id);
        res.json({ success: true, message: 'Product deleted' });
    } catch (err) {
        next(err);
    }
};

module.exports = { createProduct, getProducts, getProductById, updateProduct, adjustStock, deleteProduct };
