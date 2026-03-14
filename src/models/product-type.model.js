const mongoose = require('mongoose');

const productTypeSchema = new mongoose.Schema({
    name: {
        type: String,
        required: [true, 'Product type name is required'],
        unique: true,
        trim: true,
    },
    description: {
        type: String,
        trim: true,
        default: '',
    },
    icon: {
        type: String,
        trim: true,
        default: 'bi-box-seam',
    },
}, { timestamps: true });

module.exports = mongoose.model('ProductType', productTypeSchema);
