const jwt = require('jsonwebtoken');
const policy = require('../policies/policy');

const verifyAccessToken = (req, res, next) => {
    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token) {
        return res.status(401).json({ success: false, message: 'Access token required' });
    }

    try {
        const decoded = jwt.verify(token, process.env.ACCESS_TOKEN_SECRET);
        req.user = decoded;
        next();
    } catch (err) {
        if (err.name === 'TokenExpiredError') {
            return res.status(401).json({ success: false, message: 'Access token expired' });
        }
        return res.status(403).json({ success: false, message: 'Invalid access token' });
    }
};

function checkPolicy(action, resource) {
  return (req, res, next) => {
    if (policy(req.user, action, resource)) {
      next();
    } else {
      res.status(403).json({ success: false, message: 'Forbidden' });
    }
  };
}

module.exports = { verifyAccessToken, checkPolicy };
