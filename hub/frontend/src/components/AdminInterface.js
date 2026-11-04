import React, { useState, useEffect } from 'react';
import {
  Box,
  AppBar,
  Toolbar,
  Typography,
  Container,
  Grid,
  Card,
  CardContent,
  CardHeader,
  List,
  ListItem,
  ListItemText,
  ListItemIcon,
  Chip,
  Button,
  IconButton,
  Alert,
  CircularProgress,
  Divider,
  Avatar,
  Fab,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Switch,
  FormControlLabel,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
} from '@mui/material';
import {
  Dashboard as DashboardIcon,
  Devices as DevicesIcon,
  People as PeopleIcon,
  Refresh as RefreshIcon,
  Computer as ComputerIcon,
  Smartphone as SmartphoneIcon,
  Router as RouterIcon,
  Tv as TvIcon,
  DeviceUnknown as UnknownDeviceIcon,
  Chat as ChatIcon,
  AdminPanelSettings as AdminIcon,
  NetworkCheck as NetworkIcon,
  Memory as MemoryIcon,
  Storage as StorageIcon,
} from '@mui/icons-material';
import axios from 'axios';

const AdminInterface = () => {
  const [devices, setDevices] = useState([]);
  const [clients, setClients] = useState([]);
  const [systemInfo, setSystemInfo] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [showAllDevices, setShowAllDevices] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);

  const fetchDevices = async () => {
    setLoading(true);
    try {
      const response = await axios.get(`/api/devices?ekatra_only=${!showAllDevices}`);
      if (response.data && response.data.success && response.data.data && response.data.data.devices) {
        setDevices(response.data.data.devices);
      }
    } catch (err) {
      setError('Failed to fetch devices: ' + (err.response?.data?.error || err.message));
    } finally {
      setLoading(false);
    }
  };

  const fetchClients = async () => {
    try {
      const response = await axios.get('/api/clients/active');
      if (response.data && response.data.success && response.data.data && response.data.data.clients) {
        setClients(response.data.data.clients);
      }
    } catch (err) {
      console.error('Failed to fetch clients:', err);
    }
  };

  const fetchSystemInfo = async () => {
    try {
      // This would be a new endpoint to get system stats
      const response = await axios.get('/api/system/info');
      if (response.data && response.data.success && response.data.data) {
        setSystemInfo(response.data.data);
      }
    } catch (err) {
      console.error('Failed to fetch system info:', err);
    }
  };

  useEffect(() => {
    fetchDevices();
    fetchClients();
    fetchSystemInfo();

    // Refresh data every 30 seconds
    const interval = setInterval(() => {
      fetchDevices();
      fetchClients();
      fetchSystemInfo();
    }, 30000);

    return () => clearInterval(interval);
  }, [showAllDevices]);

  const getDeviceIcon = (deviceType) => {
    switch (deviceType?.toLowerCase()) {
      case 'computer':
      case 'laptop':
        return <ComputerIcon />;
      case 'smartphone':
      case 'mobile':
        return <SmartphoneIcon />;
      case 'router':
      case 'gateway':
        return <RouterIcon />;
      case 'tv':
      case 'smart tv':
        return <TvIcon />;
      default:
        return <UnknownDeviceIcon />;
    }
  };

  const getDeviceColor = (deviceType) => {
    switch (deviceType?.toLowerCase()) {
      case 'computer':
      case 'laptop':
        return 'primary';
      case 'smartphone':
      case 'mobile':
        return 'secondary';
      case 'router':
      case 'gateway':
        return 'warning';
      default:
        return 'default';
    }
  };

  return (
    <Box sx={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <AppBar position="static" elevation={1}>
        <Toolbar>
          <AdminIcon sx={{ mr: 2 }} />
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            Ekatra AI - Admin Dashboard
          </Typography>
          <Button 
            color="inherit" 
            onClick={() => window.open('/client', '_blank')}
            startIcon={<ChatIcon />}
            sx={{ mr: 1 }}
          >
            Client View
          </Button>
          <IconButton 
            color="inherit" 
            onClick={() => setSettingsOpen(true)}
            title="Settings"
          >
            <AdminIcon />
          </IconButton>
        </Toolbar>
      </AppBar>

      {/* Main Content */}
      <Container maxWidth="xl" sx={{ mt: 3, mb: 3, flex: 1, overflow: 'auto' }}>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <Grid container spacing={3}>
          {/* System Overview */}
          <Grid item xs={12} md={4}>
            <Card>
              <CardHeader
                title="System Overview"
                avatar={<DashboardIcon />}
                action={
                  <IconButton onClick={fetchSystemInfo}>
                    <RefreshIcon />
                  </IconButton>
                }
              />
              <CardContent>
                {systemInfo ? (
                  <List dense>
                    <ListItem>
                      <ListItemIcon>
                        <MemoryIcon />
                      </ListItemIcon>
                      <ListItemText 
                        primary="Memory Usage" 
                        secondary={systemInfo.memory || "N/A"} 
                      />
                    </ListItem>
                    <ListItem>
                      <ListItemIcon>
                        <StorageIcon />
                      </ListItemIcon>
                      <ListItemText 
                        primary="Storage" 
                        secondary={systemInfo.storage || "N/A"} 
                      />
                    </ListItem>
                    <ListItem>
                      <ListItemIcon>
                        <NetworkIcon />
                      </ListItemIcon>
                      <ListItemText 
                        primary="Network Status" 
                        secondary="Connected" 
                      />
                    </ListItem>
                  </List>
                ) : (
                  <Box sx={{ display: 'flex', justifyContent: 'center', p: 2 }}>
                    <CircularProgress size={20} />
                  </Box>
                )}
              </CardContent>
            </Card>
          </Grid>

          {/* Active Clients */}
          <Grid item xs={12} md={4}>
            <Card>
              <CardHeader
                title="Active Clients"
                avatar={<PeopleIcon />}
                action={
                  <Chip 
                    label={clients.length} 
                    color="success" 
                    size="small"
                  />
                }
              />
              <CardContent>
                {clients.length > 0 ? (
                  <List dense>
                    {clients.slice(0, 5).map((client, index) => (
                      <ListItem key={index}>
                        <ListItemIcon>
                          <Avatar sx={{ width: 24, height: 24 }}>
                            {client.ip?.split('.').pop() || '?'}
                          </Avatar>
                        </ListItemIcon>
                        <ListItemText 
                          primary={client.ip || 'Unknown IP'}
                          secondary={`Connected: ${client.connected_at || 'Unknown'}`}
                        />
                      </ListItem>
                    ))}
                    {clients.length > 5 && (
                      <ListItem>
                        <ListItemText 
                          primary={`+${clients.length - 5} more clients`}
                          secondary="Click to view all"
                        />
                      </ListItem>
                    )}
                  </List>
                ) : (
                  <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
                    No active clients
                  </Typography>
                )}
              </CardContent>
            </Card>
          </Grid>

          {/* Quick Actions */}
          <Grid item xs={12} md={4}>
            <Card>
              <CardHeader
                title="Quick Actions"
                avatar={<AdminIcon />}
              />
              <CardContent>
                <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                  <Button
                    variant="outlined"
                    startIcon={<RefreshIcon />}
                    onClick={fetchDevices}
                    disabled={loading}
                    fullWidth
                  >
                    Refresh Devices
                  </Button>
                  <FormControlLabel
                    control={
                      <Switch
                        checked={showAllDevices}
                        onChange={(e) => setShowAllDevices(e.target.checked)}
                      />
                    }
                    label="Show All Network Devices"
                  />
                  <Divider sx={{ my: 1 }} />
                  <Button
                    variant="contained"
                    startIcon={<ChatIcon />}
                    onClick={() => window.open('/client', '_blank')}
                    fullWidth
                  >
                    Open Client Interface
                  </Button>
                </Box>
              </CardContent>
            </Card>
          </Grid>

          {/* Discovered Devices */}
          <Grid item xs={12}>
            <Card>
              <CardHeader
                title="Discovered Devices"
                avatar={<DevicesIcon />}
                action={
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Chip 
                      label={`${devices.length} devices`} 
                      color="primary" 
                      size="small"
                    />
                    <IconButton onClick={fetchDevices} disabled={loading}>
                      {loading ? <CircularProgress size={20} /> : <RefreshIcon />}
                    </IconButton>
                  </Box>
                }
              />
              <CardContent>
                {devices.length > 0 ? (
                  <TableContainer component={Paper} variant="outlined">
                    <Table>
                      <TableHead>
                        <TableRow>
                          <TableCell>Device</TableCell>
                          <TableCell>IP Address</TableCell>
                          <TableCell>MAC Address</TableCell>
                          <TableCell>Hostname</TableCell>
                          <TableCell>Type</TableCell>
                          <TableCell>Status</TableCell>
                        </TableRow>
                      </TableHead>
                      <TableBody>
                        {devices.map((device, index) => (
                          <TableRow key={index} hover>
                            <TableCell>
                              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                                {getDeviceIcon(device.device_type)}
                                <Typography variant="body2">
                                  {device.hostname || device.ip || 'Unknown Device'}
                                </Typography>
                              </Box>
                            </TableCell>
                            <TableCell>
                              <Typography variant="body2" fontFamily="monospace">
                                {device.ip || 'N/A'}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Typography variant="body2" fontFamily="monospace">
                                {device.mac || 'N/A'}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Typography variant="body2">
                                {device.hostname || 'Unknown'}
                              </Typography>
                            </TableCell>
                            <TableCell>
                              <Chip
                                label={device.device_type || 'Unknown'}
                                color={getDeviceColor(device.device_type)}
                                size="small"
                                variant="outlined"
                              />
                            </TableCell>
                            <TableCell>
                              <Chip
                                label={device.status || 'Unknown'}
                                color={device.status === 'active' ? 'success' : 'default'}
                                size="small"
                              />
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                ) : (
                  <Box sx={{ textAlign: 'center', py: 4 }}>
                    <DevicesIcon sx={{ fontSize: 48, color: 'text.secondary', mb: 2 }} />
                    <Typography variant="h6" color="text.secondary" gutterBottom>
                      No devices discovered
                    </Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                      Try refreshing or check your network connection
                    </Typography>
                    <Button
                      variant="outlined"
                      startIcon={<RefreshIcon />}
                      onClick={fetchDevices}
                      disabled={loading}
                    >
                      Refresh Now
                    </Button>
                  </Box>
                )}
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Container>

      {/* Floating Action Button for Quick Refresh */}
      <Fab
        color="primary"
        sx={{ position: 'fixed', bottom: 16, right: 16 }}
        onClick={fetchDevices}
        disabled={loading}
      >
        {loading ? <CircularProgress size={24} color="inherit" /> : <RefreshIcon />}
      </Fab>

      {/* Settings Dialog */}
      <Dialog open={settingsOpen} onClose={() => setSettingsOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Admin Settings</DialogTitle>
        <DialogContent>
          <Box sx={{ pt: 1 }}>
            <FormControlLabel
              control={
                <Switch
                  checked={showAllDevices}
                  onChange={(e) => setShowAllDevices(e.target.checked)}
                />
              }
              label="Show All Network Devices (not just Ekatra-connected)"
            />
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              When enabled, shows all devices on the network, not just those connected to Ekatra AI
            </Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSettingsOpen(false)}>Close</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
};

export default AdminInterface;
