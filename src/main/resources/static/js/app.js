const API_URL = 'http://localhost:8080';
let currentToken = localStorage.getItem('conveyor_token');
let pollInterval = null;

// DOM Elements
const authSection = document.getElementById('auth-section');
const dashboardSection = document.getElementById('dashboard-section');
const logoutBtn = document.getElementById('logout-btn');
const authForm = document.getElementById('auth-form');
const authError = document.getElementById('auth-error');
const dropzone = document.getElementById('dropzone');
const fileInput = document.getElementById('file-input');
const uploadBtn = document.getElementById('upload-btn');
const jobsGrid = document.getElementById('jobs-grid');
const jobTemplate = document.getElementById('job-template');

// Initialize
function init() {
    if (currentToken) {
        showDashboard();
    } else {
        showAuth();
    }
}

// ── Authentication ──

function showAuth() {
    authSection.classList.remove('hidden');
    dashboardSection.classList.add('hidden');
    logoutBtn.classList.add('hidden');
    if (pollInterval) clearInterval(pollInterval);
}

function showDashboard() {
    authSection.classList.add('hidden');
    dashboardSection.classList.remove('hidden');
    logoutBtn.classList.remove('hidden');
    fetchJobs();
    pollInterval = setInterval(fetchJobs, 2000);
}

authForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    handleAuth('/auth/login');
});

document.getElementById('register-btn').addEventListener('click', () => {
    if (authForm.checkValidity()) {
        handleAuth('/auth/register');
    } else {
        authForm.reportValidity();
    }
});

async function handleAuth(endpoint) {
    const email = document.getElementById('email').value;
    const password = document.getElementById('password').value;
    authError.classList.add('hidden');

    try {
        const response = await fetch(`${API_URL}${endpoint}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        if (!response.ok) throw new Error('Authentication failed');
        
        const data = await response.json();
        currentToken = data.token;
        localStorage.setItem('conveyor_token', currentToken);
        showDashboard();
    } catch (err) {
        authError.textContent = 'Error: Invalid credentials or email already exists.';
        authError.classList.remove('hidden');
    }
}

logoutBtn.addEventListener('click', () => {
    currentToken = null;
    localStorage.removeItem('conveyor_token');
    showAuth();
});

// ── Upload ──

uploadBtn.addEventListener('click', () => fileInput.click());

dropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropzone.classList.add('dragover');
});

dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));

dropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropzone.classList.remove('dragover');
    if (e.dataTransfer.files.length) {
        Array.from(e.dataTransfer.files).forEach(uploadFile);
    }
});

fileInput.addEventListener('change', (e) => {
    if (e.target.files.length) {
        Array.from(e.target.files).forEach(uploadFile);
        e.target.value = ''; // Reset value to allow selecting the same files again
    }
});

async function uploadFile(file) {
    if (!file.type.match('image.*')) {
        alert('Please select an image file (JPG/PNG).');
        return;
    }

    const formData = new FormData();
    formData.append('file', file);

    try {
        // Optimistic UI update could go here
        uploadBtn.textContent = 'Uploading...';
        uploadBtn.disabled = true;

        const response = await fetch(`${API_URL}/jobs/image`, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${currentToken}`
            },
            body: formData
        });

        if (response.ok) {
            uploadBtn.textContent = 'Upload Successful!';
            setTimeout(() => {
                uploadBtn.textContent = 'Select Files';
                uploadBtn.disabled = false;
            }, 2000);
            fetchJobs(); // Refresh the list
        } else {
            if (response.status === 401 || response.status === 403) {
                logoutBtn.click();
                return;
            }
            throw new Error('Upload failed');
        }
    } catch (error) {
        alert(error.message);
        uploadBtn.textContent = 'Select Files';
        uploadBtn.disabled = false;
    }
    fileInput.value = '';
}

// ── Jobs Polling & Render ──

async function fetchJobs() {
    try {
        const response = await fetch(`${API_URL}/jobs?size=12`, {
            headers: {
                'Authorization': `Bearer ${currentToken}`
            }
        });

        if (!response.ok) {
            if (response.status === 401 || response.status === 403) {
                logoutBtn.click();
                return;
            }
            throw new Error('Failed to fetch jobs');
        }

        const page = await response.json();
        renderJobs(page.jobs || []);
    } catch (err) {
        console.error(err);
    }
}

function renderJobs(jobs) {
    jobsGrid.innerHTML = '';
    
    if (jobs.length === 0) {
        jobsGrid.innerHTML = '<p style="color: var(--text-secondary); grid-column: 1/-1; text-align: center; padding: 2rem;">No jobs yet. Upload an image to start processing.</p>';
        return;
    }

    jobs.forEach(job => {
        const clone = jobTemplate.content.cloneNode(true);
        const card = clone.querySelector('.job-card');
        
        clone.querySelector('.job-id').textContent = `Job #${job.id}`;
        clone.querySelector('.job-date').textContent = new Date(job.createdAt).toLocaleString();
        
        const badge = clone.querySelector('.job-badge');
        badge.textContent = job.status;
        badge.classList.add(`status-${job.status}`);

        const deleteBtn = clone.querySelector('.btn-delete');
        deleteBtn.addEventListener('click', async () => {
            if (!confirm('Are you sure you want to delete this job and its files?')) return;
            try {
                const response = await fetch(`${API_URL}/jobs/${job.id}`, {
                    method: 'DELETE',
                    headers: { 'Authorization': `Bearer ${currentToken}` }
                });
                if (!response.ok && response.status !== 404) throw new Error('Failed to delete job');
                fetchJobs(); // Refresh the list
            } catch (err) {
                alert(err.message);
            }
        });

        // State handlers
        if (job.status === 'PENDING' || job.status === 'PROCESSING') {
            clone.querySelector('.job-progress-container').classList.remove('hidden');
        } 
        else if (job.status === 'COMPLETED') {
            const resultSection = clone.querySelector('.job-result');
            resultSection.classList.remove('hidden');
            
            // Generate API URL for thumbnail
            const thumbUrl = `${API_URL}/jobs/${job.id}/thumbnail?token=${currentToken}`;
            
            const img = clone.querySelector('.job-thumbnail');
            img.src = thumbUrl;
            img.onerror = () => { img.style.display = 'none'; };

            clone.querySelector('.meta-format').textContent = job.metadata.format || 'Unknown';
            clone.querySelector('.meta-size').textContent = job.metadata.fileSizeBytes ? 
                (job.metadata.fileSizeBytes / 1024).toFixed(1) + ' KB' : 'N/A';
            
            const duration = new Date(job.updatedAt) - new Date(job.createdAt);
            clone.querySelector('.meta-time').textContent = duration > 0 ? `${duration}ms` : '<1ms';
        }
        else if (job.status === 'FAILED') {
            const errorSection = clone.querySelector('.job-error');
            errorSection.classList.remove('hidden');
            clone.querySelector('.error-text').textContent = job.errorReason || 'Unknown failure occurred during processing.';
        }

        jobsGrid.appendChild(clone);
    });
}

// Boot
init();
