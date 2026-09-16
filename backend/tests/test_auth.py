def test_register_user(client):
    response = client.post(
        "/api/v1/auth/register",
        json={"username": "testuser", "password": "testpassword", "business_name": "Test Business"}
    )
    assert response.status_code == 200
    assert response.json()["username"] == "testuser"
    assert "id" in response.json()

def test_login_user(client):
    # Register first
    client.post(
        "/api/v1/auth/register",
        json={"username": "testuser", "password": "testpassword"}
    )

    # Login
    response = client.post(
        "/api/v1/auth/login",
        data={"username": "testuser", "password": "testpassword"}
    )
    assert response.status_code == 200
    assert "access_token" in response.json()
    assert response.json()["token_type"] == "bearer"

def test_get_me(client):
    # Register and login
    client.post("/api/v1/auth/register", json={"username": "testuser", "password": "testpassword"})
    login_res = client.post("/api/v1/auth/login", data={"username": "testuser", "password": "testpassword"})
    token = login_res.json()["access_token"]

    response = client.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {token}"}
    )
    assert response.status_code == 200
    assert response.json()["username"] == "testuser"
