import React from 'react';
import ReactDOM from 'react-dom/client';

import { App } from './app/App';
import './styles/foundation.css';

const root = document.getElementById('root');
if (!root) {
  throw new Error('PeeGeeQ Cache management UI root element is missing');
}

ReactDOM.createRoot(root).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
