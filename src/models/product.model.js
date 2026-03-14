const mongoose = require('mongoose');

const productSchema = new mongoose.Schema(
    {
        name: {
            type: String,
            required: [true, 'Product name is required'],
            trim: true,
        },
        sku: {
            type: String,
            required: [true, 'SKU is required'],
            unique: true,
            trim: true,
            uppercase: true,
        },
        description: {
            type: String,
            trim: true,
            default: '',
        },
        price: {
            type: Number,
            required: [true, 'Price is required'],
            min: [0, 'Price cannot be negative'],
        },
        unit: {
            type: String,
            required: [true, 'Unit is required'],
            enum: ['kg', 'g', 'liter', 'ml', 'piece', 'box', 'pack', 'dozen', 'bag'],
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
        supplier: {
            type: String,
            trim: true,
            default: '',
        },
        status: {
            type: String,
            enum: ['active', 'inactive', 'discontinued'],
            default: 'active',
        },
        productType: {
            type: mongoose.Schema.Types.ObjectId,
            ref: 'ProductType',
            default: null,
        },
        // Food-specific optional fields
        category: {
            type: String,
            enum: ['fresh_produce', 'dairy', 'meat', 'seafood', 'bakery', 'beverages', 'snacks', 'condiments', 'frozen', 'dry_goods', 'other', null],
            default: null,
        },
        expiryTracking: {
            type: Boolean,
            default: false,
        },
        shelfLifeDays: {
            type: Number,
            min: [1, 'Shelf life must be at least 1 day'],
            default: null,
        },
        ingredients: [
            {
                ingredient: {
                    type: mongoose.Schema.Types.ObjectId,
                    ref: 'Ingredient',
                },
                quantity: {
                    type: Number,
                    min: [0, 'Quantity cannot be negative'],
                },
            },
        ],
    },
    { timestamps: true }
);

productSchema.virtual('isLowStock').get(function () {
    return this.stock <= this.lowStockThreshold;
});

module.exports = mongoose.model('Product', productSchema);
