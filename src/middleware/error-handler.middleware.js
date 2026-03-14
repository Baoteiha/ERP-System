const errorHandler = (err, _req, res, _next) => {
    console.error(err.stack);

    if (err.name === 'ValidationError') {
        const errors = Object.entries(err.errors).map(([field, e]) => ({
            field,
            message: e.message,
        }));
        return res.status(400).json({ success: false, errors });
    }

    if (err.code === 11000) {
        const field = Object.keys(err.keyValue)[0];
        return res.status(409).json({ success: false, message: `${field} already exists` });
    }

    const status = err.status || err.statusCode || 500;
    const message = err.message || 'Internal server error';

    res.status(status).json({ success: false, message });
};

module.exports = errorHandler;
