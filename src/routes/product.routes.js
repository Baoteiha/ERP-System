const express = require('express');
const router = express.Router();

const { verifyAccessToken, checkPolicy } = require('../middleware/authentication.middleware');
const {
    createProduct,
    getProducts,
    getProductById,
    updateProduct,
    adjustStock,
    deleteProduct,
} = require('../controllers/product.controller');

// All product routes require authentication
router.use(verifyAccessToken);

router.get('/', checkPolicy('read', 'product'), getProducts);
router.get('/:id', checkPolicy('read', 'product'), getProductById);
router.post('/', checkPolicy('write', 'product'), createProduct);
router.patch('/:id', checkPolicy('write', 'product'), updateProduct);
router.patch('/:id/stock', checkPolicy('write', 'product'), adjustStock);
router.delete('/:id', checkPolicy('delete', 'product'), deleteProduct);

module.exports = router;
