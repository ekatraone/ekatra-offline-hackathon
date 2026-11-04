import React, { useState, useRef, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  TextField,
  IconButton,
  Typography,
  Paper,
  AppBar,
  Toolbar,
  Chip,
  CircularProgress,
  Alert,
  Button,
} from '@mui/material';
import {
  Send as SendIcon,
  Person as PersonIcon,
  Android as AIIcon,
  Settings as SettingsIcon,
} from '@mui/icons-material';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import axios from 'axios';

const ClientInterface = () => {
  const [messages, setMessages] = useState([]);
  const [inputMessage, setInputMessage] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);
  const messagesEndRef = useRef(null);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const formatMarkdown = (text) => {
    const rawMarkup = marked(text, {
      breaks: true,
      gfm: true,
    });
    return DOMPurify.sanitize(rawMarkup);
  };

  const handleSendMessage = async () => {
    if (!inputMessage.trim() || isLoading) return;

    const userMessage = {
      id: Date.now(),
      text: inputMessage,
      sender: 'user',
      timestamp: new Date().toLocaleTimeString(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInputMessage('');
    setIsLoading(true);
    setError(null);

    try {
      const response = await axios.post('/api/generate', {
        message: inputMessage,
      }, {
        headers: {
          'Content-Type': 'application/json',
        },
        timeout: 30000,
      });

      if (response.data && response.data.success && response.data.data && response.data.data.response) {
        const aiMessage = {
          id: Date.now() + 1,
          text: response.data.data.response,
          sender: 'ai',
          timestamp: new Date().toLocaleTimeString(),
        };
        setMessages(prev => [...prev, aiMessage]);
      } else {
        throw new Error('Invalid response format');
      }
    } catch (err) {
      console.error('Error sending message:', err);
      setError(
        err.response?.data?.error || 
        err.message || 
        'Failed to send message. Please try again.'
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  return (
    <Box sx={{ height: '100vh', display: 'flex', flexDirection: 'column' }}>
      {/* Header */}
      <AppBar position="static" elevation={1}>
        <Toolbar>
          <AIIcon sx={{ mr: 2 }} />
          <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
            Ekatra AI Assistant
          </Typography>
          <Chip 
            label="Online" 
            color="success" 
            variant="outlined" 
            size="small"
            sx={{ mr: 2, color: 'white', borderColor: 'rgba(255,255,255,0.5)' }}
          />
          <IconButton 
            color="inherit" 
            onClick={() => window.open('/admin', '_blank')}
            title="Admin Panel"
          >
            <SettingsIcon />
          </IconButton>
        </Toolbar>
      </AppBar>

      {/* Chat Area */}
      <Box 
        sx={{ 
          flex: 1, 
          overflow: 'auto', 
          p: 2,
          backgroundColor: '#f5f5f5',
        }}
      >
        {messages.length === 0 && (
          <Box sx={{ textAlign: 'center', mt: 4 }}>
            <AIIcon sx={{ fontSize: 64, color: 'text.secondary', mb: 2 }} />
            <Typography variant="h5" color="text.secondary" gutterBottom>
              Welcome to Ekatra AI
            </Typography>
            <Typography variant="body1" color="text.secondary">
              Start a conversation by typing a message below
            </Typography>
          </Box>
        )}

        {messages.map((message) => (
          <Box
            key={message.id}
            sx={{
              display: 'flex',
              justifyContent: message.sender === 'user' ? 'flex-end' : 'flex-start',
              mb: 2,
            }}
          >
            <Card
              sx={{
                maxWidth: '70%',
                minWidth: '200px',
                backgroundColor: message.sender === 'user' ? 'primary.main' : 'background.paper',
                color: message.sender === 'user' ? 'primary.contrastText' : 'text.primary',
              }}
            >
              <CardContent sx={{ pb: '16px !important' }}>
                <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
                  {message.sender === 'user' ? (
                    <PersonIcon sx={{ mr: 1, fontSize: 20 }} />
                  ) : (
                    <AIIcon sx={{ mr: 1, fontSize: 20, color: 'primary.main' }} />
                  )}
                  <Typography variant="caption" sx={{ opacity: 0.8 }}>
                    {message.sender === 'user' ? 'You' : 'Ekatra AI'} • {message.timestamp}
                  </Typography>
                </Box>
                {message.sender === 'ai' ? (
                  <Typography
                    variant="body1"
                    dangerouslySetInnerHTML={{
                      __html: formatMarkdown(message.text),
                    }}
                    sx={{
                      '& p': { mb: 1, '&:last-child': { mb: 0 } },
                      '& pre': { 
                        backgroundColor: 'grey.100', 
                        p: 1, 
                        borderRadius: 1,
                        overflow: 'auto',
                      },
                      '& code': { 
                        backgroundColor: 'grey.100', 
                        px: 0.5, 
                        borderRadius: 0.5,
                        fontSize: '0.875em',
                      },
                    }}
                  />
                ) : (
                  <Typography variant="body1" sx={{ whiteSpace: 'pre-wrap' }}>
                    {message.text}
                  </Typography>
                )}
              </CardContent>
            </Card>
          </Box>
        ))}

        {isLoading && (
          <Box sx={{ display: 'flex', justifyContent: 'flex-start', mb: 2 }}>
            <Card sx={{ maxWidth: '70%', backgroundColor: 'background.paper' }}>
              <CardContent>
                <Box sx={{ display: 'flex', alignItems: 'center' }}>
                  <AIIcon sx={{ mr: 1, fontSize: 20, color: 'primary.main' }} />
                  <Typography variant="caption" sx={{ opacity: 0.8, mr: 2 }}>
                    Ekatra AI is thinking...
                  </Typography>
                  <CircularProgress size={16} />
                </Box>
              </CardContent>
            </Card>
          </Box>
        )}

        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
            <Button size="small" onClick={() => setError(null)} sx={{ ml: 2 }}>
              Dismiss
            </Button>
          </Alert>
        )}

        <div ref={messagesEndRef} />
      </Box>

      {/* Input Area */}
      <Paper 
        elevation={3} 
        sx={{ 
          p: 2, 
          backgroundColor: 'background.paper',
          borderTop: '1px solid',
          borderColor: 'divider',
        }}
      >
        <Box sx={{ display: 'flex', gap: 1 }}>
          <TextField
            fullWidth
            variant="outlined"
            placeholder="Type your message here..."
            value={inputMessage}
            onChange={(e) => setInputMessage(e.target.value)}
            onKeyPress={handleKeyPress}
            disabled={isLoading}
            multiline
            maxRows={4}
            sx={{
              '& .MuiOutlinedInput-root': {
                borderRadius: 3,
              },
            }}
          />
          <IconButton
            color="primary"
            onClick={handleSendMessage}
            disabled={!inputMessage.trim() || isLoading}
            sx={{
              backgroundColor: 'primary.main',
              color: 'white',
              borderRadius: 3,
              px: 3,
              '&:hover': {
                backgroundColor: 'primary.dark',
              },
              '&:disabled': {
                backgroundColor: 'grey.300',
                color: 'grey.500',
              },
            }}
          >
            <SendIcon />
          </IconButton>
        </Box>
      </Paper>
    </Box>
  );
};

export default ClientInterface;
