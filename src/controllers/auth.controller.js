const authService = require('../services/auth.service');

const REFRESH_COOKIE_OPTIONS = {
    httpOnly: true,
    secure: process.env.NODE_ENV === 'production',
    sameSite: 'strict',
    maxAge: 7 * 24 * 60 * 60 * 1000,
};

const register = async (req, res, next) => {
    try {
        const { username, email, password, role } = req.body;
        if (!username || !email || !password) {
            return res.status(400).json({ success: false, message: 'Username, email, and password are required' });
        }

        const user = await authService.registerUser({ username, email, password, role });
        res.status(201).json({ success: true, message: 'User registered successfully', data: user });
    } catch (err) {
        next(err);
    }
};

const login = async (req, res, next) => {
    try {
        const { email, password } = req.body;
        if (!email || !password) {
            return res.status(400).json({ success: false, message: 'Email and password are required' });
        }

        const { accessToken, refreshToken, user } = await authService.loginUser({ email, password });

        res.cookie('refreshToken', refreshToken, REFRESH_COOKIE_OPTIONS);
        res.json({ success: true, data: { accessToken, user } });
    } catch (err) {
        next(err);
    }
};

const refresh = async (req, res, next) => {
    try {
        const token = req.cookies?.refreshToken;
        const result = await authService.refreshAccessToken(token);
        res.json({ success: true, data: result });
    } catch (err) {
        next(err);
    }
};

const logout = async (req, res, next) => {
    try {
        const token = req.cookies?.refreshToken;
        await authService.logoutUser(token);
        res.clearCookie('refreshToken');
        res.json({ success: true, message: 'Logged out successfully' });
    } catch (err) {
        next(err);
    }
};

module.exports = { register, login, refresh, logout };
