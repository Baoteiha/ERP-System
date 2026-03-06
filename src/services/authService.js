const jwt = require('jsonwebtoken');
const User = require('../models/User');

const generateAccessToken = (user) => {
    return jwt.sign(
        { id: user._id, role: user.role },
        process.env.ACCESS_TOKEN_SECRET,
        { expiresIn: process.env.ACCESS_TOKEN_EXPIRES_IN || '15m' }
    );
};

const generateRefreshToken = (user) => {
    return jwt.sign(
        { id: user._id },
        process.env.REFRESH_TOKEN_SECRET,
        { expiresIn: process.env.REFRESH_TOKEN_EXPIRES_IN || '7d' }
    );
};

const registerUser = async ({ username, email, password, role }) => {
    const existingUser = await User.findOne({ $or: [{ email }, { username }] });
    if (existingUser) {
        const error = new Error('Username or email already in use');
        error.statusCode = 409;
        throw error;
    }

    const user = await User.create({ username, email, password, role });
    return { id: user._id, username: user.username, email: user.email, role: user.role };
};

const loginUser = async ({ email, password }) => {
    const user = await User.findOne({ email });
    if (!user || !(await user.comparePassword(password))) {
        const error = new Error('Invalid email or password');
        error.statusCode = 401;
        throw error;
    }

    const accessToken = generateAccessToken(user);
    const refreshToken = generateRefreshToken(user);

    user.refreshTokens.push(refreshToken);
    await user.save();

    return {
        accessToken,
        refreshToken,
        user: { id: user._id, username: user.username, email: user.email, role: user.role },
    };
};

const refreshAccessToken = async (token) => {
    if (!token) {
        const error = new Error('Refresh token required');
        error.statusCode = 401;
        throw error;
    }

    let decoded;
    try {
        decoded = jwt.verify(token, process.env.REFRESH_TOKEN_SECRET);
    } catch {
        const error = new Error('Invalid or expired refresh token');
        error.statusCode = 403;
        throw error;
    }

    const user = await User.findById(decoded.id);
    if (!user || !user.refreshTokens.includes(token)) {
        const error = new Error('Refresh token revoked');
        error.statusCode = 403;
        throw error;
    }

    return { accessToken: generateAccessToken(user) };
};

const logoutUser = async (token) => {
    if (token) {
        await User.findOneAndUpdate(
            { refreshTokens: token },
            { $pull: { refreshTokens: token } }
        );
    }
};

module.exports = { registerUser, loginUser, refreshAccessToken, logoutUser };
