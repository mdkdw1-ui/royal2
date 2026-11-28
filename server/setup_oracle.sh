#!/usr/bin/env bash
set -e

if [ ! -f /swapfile ]; then
  sudo fallocate -l 1G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

sudo apt-get update
sudo apt-get install -y python3-venv python3-pip libglib2.0-0 libgl1

mkdir -p ~/royal2-server
cd ~/royal2-server
python3 -m venv venv
source venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 8000 -j ACCEPT || true
sudo netfilter-persistent save || true

sudo tee /etc/systemd/system/royal2.service > /dev/null <<EOF
[Unit]
Description=Royal2 Zero-Mistake Vision Server
After=network.target

[Service]
User=$USER
WorkingDirectory=$HOME/royal2-server
ExecStart=$HOME/royal2-server/venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000 --workers 1
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable royal2
sudo systemctl restart royal2
echo "✅ 서버 실행 완료: http://$(curl -s ifconfig.me):8000"
