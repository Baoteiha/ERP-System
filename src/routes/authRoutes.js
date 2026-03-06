const express = require('express');
const router = express.Router();
const { register, login, refresh, logout } = require('../controllers/authController');
const { verifyAccessToken } = require('../middleware/authentication');

router.post('/register', register);
router.post('/login', login);
router.post('/refresh', refresh);
router.post('/logout', logout);

router.get('/me', verifyAccessToken, (req, res) => {
    res.json({ success: true, data: req.user });
});

module.exports = router;
