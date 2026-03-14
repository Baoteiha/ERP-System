const express = require('express');
const router = express.Router();
const ProductType = require('../models/product-type.model');
const { verifyAccessToken, checkPolicy } = require('../middleware/authentication.middleware');

router.use(verifyAccessToken);

// List all product types
router.get('/', async (req, res, next) => {
    try {
        const types = await ProductType.find().sort({ name: 1 });
        res.json({ success: true, data: types });
    } catch (err) {
        next(err);
    }
});

// Create a product type (admin/manager only)
router.post('/', checkPolicy('write', 'product'), async (req, res, next) => {
    try {
        const { name, description, icon } = req.body;
        if (!name) return res.status(400).json({ success: false, message: 'name is required' });
        const type = await ProductType.create({ name, description, icon });
        res.status(201).json({ success: true, data: type });
    } catch (err) {
        next(err);
    }
});

// Delete a product type (admin/manager only)
router.delete('/:id', checkPolicy('write', 'product'), async (req, res, next) => {
    try {
        const type = await ProductType.findByIdAndDelete(req.params.id);
        if (!type) return res.status(404).json({ success: false, message: 'Product type not found' });
        res.json({ success: true, message: 'Product type deleted' });
    } catch (err) {
        next(err);
    }
});

module.exports = router;
