import React from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { Box } from '@mui/material';
import ClientInterface from './components/ClientInterface';
import AdminInterface from './components/AdminInterface';

function App() {
  return (
    <Box sx={{ height: '100vh', overflow: 'hidden' }}>
      <Routes>
        <Route path="/" element={<Navigate to="/client" replace />} />
        <Route path="/client" element={<ClientInterface />} />
        <Route path="/admin" element={<AdminInterface />} />
      </Routes>
    </Box>
  );
}

export default App;
