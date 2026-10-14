// Keep local browser requests same-origin, as they are in the deployed nginx frontend.
module.exports = {
  '/api': {
    target: process.env.ARTIO_DEV_API_URL || 'http://localhost:8080',
    secure: false,
    changeOrigin: true
  }
};
