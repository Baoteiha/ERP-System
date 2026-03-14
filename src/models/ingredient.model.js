const mongoose = require('mongoose');

const ingredientSchema = new mongoose.Schema(
    {
        name: {
            type: String,
            required: [true, 'Ingredient name is required'],
            trim: true,
            unique: true,
        },
        unit: {
            type: String,
            required: [true, 'Unit is required'],
            trim: true,
        },
        stock: {
            type: Number,
            required: [true, 'Stock quantity is required'],
            min: [0, 'Stock cannot be negative'],
            default: 0,
        },
        lowStockThreshold: {
            type: Number,
            min: [0, 'Low stock threshold cannot be negative'],
            default: 10,
        },
    },
    { timestamps: true }
);

module.exports = mongoose.model('Ingredient', ingredientSchema);
