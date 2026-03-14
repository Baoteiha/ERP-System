const Product = require('../models/product.model');

const createProduct = async (data) => {
    const existing = await Product.findOne({ sku: data.sku.toUpperCase() });
    if (existing) {
        const error = new Error(`SKU "${data.sku}" is already in use`);
        error.statusCode = 409;
        throw error;
    }

    const product = await Product.create(data);
    return product.populate('productType ingredients.ingredient');
};

const getProducts = async ({ status, category, lowStock } = {}) => {
    const filter = {};

    if (status) filter.status = status;
    if (category) filter.category = category;

    const products = await Product.find(filter)
        .populate('productType')
        .populate('ingredients.ingredient')
        .sort({ createdAt: -1 });

    if (lowStock === 'true') {
        return products.filter((p) => p.stock <= p.lowStockThreshold);
    }

    return products;
};

const getProductById = async (id) => {
    const product = await Product.findById(id)
        .populate('productType')
        .populate('ingredients.ingredient');
    if (!product) {
        const error = new Error('Product not found');
        error.statusCode = 404;
        throw error;
    }
    return product;
};

const updateProduct = async (id, data) => {
    if (data.sku) {
        const conflict = await Product.findOne({ sku: data.sku.toUpperCase(), _id: { $ne: id } });
        if (conflict) {
            const error = new Error(`SKU "${data.sku}" is already in use`);
            error.statusCode = 409;
            throw error;
        }
        data.sku = data.sku.toUpperCase();
    }

    const product = await Product.findByIdAndUpdate(id, data, { new: true, runValidators: true })
        .populate('productType')
        .populate('ingredients.ingredient');
    if (!product) {
        const error = new Error('Product not found');
        error.statusCode = 404;
        throw error;
    }
    return product;
};

const adjustStock = async (id, delta) => {
    const product = await Product.findById(id);
    if (!product) {
        const error = new Error('Product not found');
        error.statusCode = 404;
        throw error;
    }

    const newStock = product.stock + delta;
    if (newStock < 0) {
        const error = new Error('Insufficient stock');
        error.statusCode = 400;
        throw error;
    }

    product.stock = newStock;
    await product.save();
    return product.populate('productType');
};

const deleteProduct = async (id) => {
    const product = await Product.findByIdAndDelete(id);
    if (!product) {
        const error = new Error('Product not found');
        error.statusCode = 404;
        throw error;
    }
};

module.exports = { createProduct, getProducts, getProductById, updateProduct, adjustStock, deleteProduct };
