const AuthForm = ({ onLogin }) => {
    const [isLoginView, setIsLoginView] = React.useState(true);

    const [username, setUsername] = React.useState("");
    const [password, setPassword] = React.useState("");
    const [passwordCheck, setPasswordCheck] = React.useState("");
    const [name, setName] = React.useState("");
    const [email, setEmail] = React.useState("");
    const [isInfoModalOpen, setIsInfoModalOpen] = React.useState(false);

    React.useEffect(() => {
        // sessionStorage에 팝업을 닫은 기록이 있는지 확인
        const hasSeenInfo = sessionStorage.getItem("infoModalClosed");
        if (!hasSeenInfo) {
            // 기록이 없으면 팝업을 띄움
            setIsInfoModalOpen(true);
        }
    }, []);

    const handleInfoModalClose = () => {
        setIsInfoModalOpen(false);
        // sessionStorage에 팝업을 닫았다는 기록을 남김
        sessionStorage.setItem("infoModalClosed", "true");
    };

    const clearInputs = () => {
        setUsername("");
        setPassword("");
        setPasswordCheck("");
        setName("");
        setEmail("");
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        try {
            if (isLoginView) {
                await API.login(username, password);
                localStorage.setItem('username', username); // 사용자 이름 저장
                onLogin();
            } else {
                const message = await API.signup(username, password, name, email, passwordCheck);
                alert(message);
                setIsLoginView(true);
                clearInputs();
            }
        } catch (err) {
            alert(err.message);
        }
    };

    return (
        <>
            <WelcomeInfoModal
                isOpen={isInfoModalOpen}
                onClose={handleInfoModalClose}
            />

            <div className="max-w-sm mx-auto mt-20 p-8 border rounded-lg shadow-lg bg-white">
                <h2 className="text-2xl font-bold mb-6 text-center">
                    {isLoginView ? 'Smart Collab 로그인' : 'Smart Collab 회원가입'}
                </h2>
                <form onSubmit={handleSubmit}>
                    <input
                        type="text"
                        placeholder="아이디"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                        className="w-full p-2 border rounded mb-4"
                        required
                    />
                    {!isLoginView && (
                        <>
                            <input
                                type="text"
                                placeholder="이름"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                className="w-full p-2 border rounded mb-4"
                                required
                            />
                            <input
                                type="email"
                                placeholder="이메일"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                className="w-full p-2 border rounded mb-4"
                            />
                        </>
                    )}
                    <input
                        type="password"
                        placeholder="비밀번호"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        className="w-full p-2 border rounded mb-4"
                        required
                    />
                    {!isLoginView && (
                        <input
                            type="password"
                            placeholder="비밀번호 확인"
                            value={passwordCheck}
                            onChange={(e) => setPasswordCheck(e.target.value)}
                            className="w-full p-2 border rounded mb-4"
                            required
                        />
                    )}
                    <button type="submit" className="w-full p-2 bg-blue-600 text-white rounded hover:bg-blue-700">
                        {isLoginView ? '로그인' : '회원가입'}
                    </button>
                </form>
                <div className="mt-4 text-center">
                    <a
                        href="#"
                        onClick={(e) => {
                            e.preventDefault();
                            setIsLoginView(!isLoginView);
                            clearInputs();
                        }}
                        className="text-sm text-blue-600 hover:underline"
                    >
                        {isLoginView ? '계정이 없으신가요? 회원가입' : '이미 계정이 있으신가요? 로그인'}
                    </a>
                </div>
            </div>
        </>
    );
};