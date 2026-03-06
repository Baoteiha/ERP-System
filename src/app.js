const express = require('express');
const path = require('path');
require('dotenv').config()

const app = express();
app.use(express.urlencoded({ extended: false }));
app.use(express.json());



