const NewFolderModal = ({ isOpen, onClose, onAction }) => {
    const [folderName, setFolderName] = React.useState("");
    if (!isOpen) return null;
    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-96 shadow-xl">
                <h2 className="text-lg font-semibold mb-4">새 폴더 만들기</h2>
                <input
                    type="text"
                    value={folderName}
                    onChange={(e) => setFolderName(e.target.value)}
                    placeholder="폴더 이름"
                    className="w-full border p-2 mb-4 rounded"
                />
                <div className="flex justify-end space-x-2">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button
                        onClick={() => {
                            if (!folderName.trim()) {
                                alert("폴더 이름을 입력해주세요.");
                                return;
                            }
                            onAction(folderName);
                            setFolderName("");
                            onClose();
                        }}
                        className="px-4 py-2 bg-blue-600 text-white rounded-md"
                    >
                        만들기
                    </button>
                </div>
            </div>
        </div>
    );
};

const RenameModal = ({ isOpen, onClose, onAction, currentName }) => {
    const [newName, setNewName] = React.useState(currentName);

    React.useEffect(() => {
        if(isOpen) {
            setNewName(currentName);
        }
    }, [isOpen, currentName]);

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-96 shadow-xl">
                <h2 className="text-lg font-semibold mb-4">이름 변경</h2>
                <input
                    type="text"
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    className="w-full border p-2 mb-4 rounded"
                />
                <div className="flex justify-end space-x-2">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button
                        onClick={() => {
                            if (!newName.trim()) {
                                alert("새 이름을 입력해주세요.");
                                return;
                            }
                            onAction(newName);
                            onClose();
                        }}
                        className="px-4 py-2 bg-blue-600 text-white rounded-md"
                    >
                        변경
                    </button>
                </div>
            </div>
        </div>
    );
};

const FolderTreeNode = ({ node, onSelect, selectedFolderId }) => {
    const [isOpen, setIsOpen] = React.useState(true);
    const isSelected = selectedFolderId === node.id;

    return (
        <div className="ml-4">
            <div
                className={`flex items-center p-1 rounded cursor-pointer ${isSelected ? 'bg-blue-200' : 'hover:bg-gray-100'}`}
                onClick={() => onSelect(node.id)}
            >
                {node.children && node.children.length > 0 && (
                    <button onClick={(e) => { e.stopPropagation(); setIsOpen(!isOpen); }} className="mr-1">
                        {isOpen
                            ? <img src="/js/images/toggle_down_icon.png" alt="collapse" className="w-4 h-4" />
                            : <img src="/js/images/toggle_right_icon.png" alt="expand" className="w-4 h-4" />
                        }
                    </button>
                )}

                <img src="/js/images/folder_icon.png" alt="folder" className="w-5 h-5 mr-2" />
                <span> {node.name}</span>
            </div>
            {isOpen && node.children && node.children.length > 0 && (
                <div className="border-l ml-2">
                    {node.children.map(childNode => (
                        <FolderTreeNode key={childNode.id} node={childNode} onSelect={onSelect} selectedFolderId={selectedFolderId} />
                    ))}
                </div>
            )}
        </div>
    );
};

const MoveModal = ({ isOpen, onClose, onAction, title, buttonText, teamContext }) => {
    const [folderTree, setFolderTree] = React.useState([]);
    const [selectedFolderId, setSelectedFolderId] = React.useState(null);

    React.useEffect(() => {
        if (isOpen) {
            setSelectedFolderId(null);
            const teamId = teamContext ? teamContext.id : null;
            API.getFolderTree(teamId)
                .then(setFolderTree)
                .catch(err => {
                    alert(err.message);
                    setFolderTree([]);
                });
        }
    }, [isOpen, teamContext]);

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-[450px] shadow-xl">
                <h2 className="text-lg font-semibold mb-4">{title}</h2>
                <div className="h-64 overflow-y-auto border rounded p-2 mb-4">
                    {folderTree.map(node => (
                        <FolderTreeNode key={node.id} node={node} onSelect={setSelectedFolderId} selectedFolderId={selectedFolderId} />
                    ))}
                </div>
                <div className="flex justify-end space-x-2">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button
                        onClick={() => onAction(selectedFolderId)}
                        disabled={!selectedFolderId}
                        className="px-4 py-2 bg-blue-600 text-white rounded-md disabled:bg-gray-300"
                    >
                        {buttonText}
                    </button>
                </div>
            </div>
        </div>
    );
};

const ShareModal = ({ isOpen, onClose, fileToShare }) => {
    const [password, setPassword] = React.useState('');
    const [expiresInDays, setExpiresInDays] = React.useState('');
    const [generatedLink, setGeneratedLink] = React.useState('');
    const [error, setError] = React.useState('');

    const handleClose = () => {
        setPassword('');
        setExpiresInDays('');
        setGeneratedLink('');
        setError('');
        onClose();
    };

    const handleCreateLink = () => {
        let expiresAt = null;
        if (expiresInDays && !isNaN(expiresInDays)) {
            const date = new Date();
            date.setDate(date.getDate() + parseInt(expiresInDays));
            expiresAt = date.toISOString();
        }

        const options = {
            password: password || null,
            expiresAt: expiresAt,
        };

        API.createShareLink(fileToShare.id, options)
            .then(link => {
                const fullLink = `${window.location.origin}${link}`;
                setGeneratedLink(fullLink);
            })
            .catch(err => setError(err.message));
    };

    const copyToClipboard = () => {
        navigator.clipboard.writeText(generatedLink)
            .then(() => alert("링크가 클립보드에 복사되었습니다."))
            .catch(err => alert("클립보드 복사 실패: " + err));
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-[500px] shadow-xl">
                <h2 className="text-lg font-semibold mb-4">공유하기: {fileToShare?.name}</h2>

                {generatedLink ? (
                    <div>
                        <p className="mb-2 text-green-700">공유 링크가 생성되었습니다.</p>
                        <div className="flex items-center space-x-2">
                            <input type="text" readOnly value={generatedLink} className="w-full border p-2 rounded bg-gray-100" />
                            <button onClick={copyToClipboard} className="p-2 rounded hover:bg-gray-200" title="링크 복사">
                                <img src="/js/images/copy_icon.png" alt="복사" className="w-4 h-4" />
                            </button>
                        </div>
                    </div>
                ) : (
                    <div className="space-y-4">
                        <div>
                            <label className="block text-sm font-medium text-gray-700">비밀번호 설정 (선택 사항)</label>
                            <input
                                type="password"
                                value={password}
                                onChange={e => setPassword(e.target.value)}
                                placeholder="비밀번호"
                                className="mt-1 w-full border p-2 rounded"
                            />
                        </div>
                        <div>
                            <label className="block text-sm font-medium text-gray-700">만료일 설정 (선택 사항)</label>
                            <input
                                type="number"
                                value={expiresInDays}
                                onChange={e => setExpiresInDays(e.target.value)}
                                placeholder="만료 기간(일) 예: 7"
                                className="mt-1 w-full border p-2 rounded"
                            />
                        </div>
                    </div>
                )}

                {error && <p className="mt-2 text-sm text-red-600">{error}</p>}

                <div className="flex justify-end space-x-2 mt-6">
                    <button onClick={handleClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">
                        {generatedLink ? "닫기" : "취소"}
                    </button>
                    {!generatedLink && (
                        <button
                            onClick={handleCreateLink}
                            className="px-4 py-2 bg-blue-600 text-white rounded-md"
                        >
                            링크 생성
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
};

const NewTeamModal = ({ isOpen, onClose, onAction }) => {
    const [teamName, setTeamName] = React.useState("");
    if (!isOpen) return null;
    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-96 shadow-xl">
                <h2 className="text-lg font-semibold mb-4">새 팀 생성</h2>
                <input
                    type="text"
                    value={teamName}
                    onChange={(e) => setTeamName(e.target.value)}
                    placeholder="팀 이름"
                    className="w-full border p-2 mb-4 rounded"
                    autoFocus
                />
                <div className="flex justify-end space-x-2">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button
                        onClick={() => {
                            if (!teamName.trim()) {
                                alert("팀 이름을 입력해주세요.");
                                return;
                            }
                            onAction(teamName);
                            setTeamName("");
                            onClose();
                        }}
                        className="px-4 py-2 bg-blue-600 text-white rounded-md"
                    >
                        생성
                    </button>
                </div>
            </div>
        </div>
    );
};

const InviteMemberModal = ({ isOpen, onClose, onAction }) => {
    const [username, setUsername] = React.useState("");
    if (!isOpen) return null;
    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-96 shadow-xl">
                <h2 className="text-lg font-semibold mb-4">새 팀원 초대</h2>
                <input
                    type="text"
                    value={username}
                    onChange={(e) => setUsername(e.target.value)}
                    placeholder="초대할 사용자의 아이디"
                    className="w-full border p-2 mb-4 rounded"
                    autoFocus
                />
                <div className="flex justify-end space-x-2">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button
                        onClick={() => {
                            if (!username.trim()) {
                                alert("사용자 아이디를 입력해주세요.");
                                return;
                            }
                            onAction(username);
                            setUsername("");
                            onClose();
                        }}
                        className="px-4 py-2 bg-blue-600 text-white rounded-md"
                    >
                        초대
                    </button>
                </div>
            </div>
        </div>
    );
};

const EditPermissionsModal = ({ isOpen, onClose, onAction, member }) => {
    const [canEdit, setCanEdit] = React.useState(member?.canEdit || false);
    const [canDelete, setCanDelete] = React.useState(member?.canDelete || false);
    const [canInvite, setCanInvite] = React.useState(member?.canInvite || false);

    React.useEffect(() => {
        if (member) {
            setCanEdit(member.canEdit);
            setCanDelete(member.canDelete);
            setCanInvite(member.canInvite);
        }
    }, [member]);

    if (!isOpen || !member) return null;

    const handleSave = () => {
        console.log("Save button clicked! Permissions:", { canEdit, canDelete, canInvite });
        onAction({ canEdit, canDelete, canInvite });
        onClose();
    };

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-96 shadow-xl">
                <h2 className="text-lg font-semibold mb-1">권한 수정</h2>
                <p className="text-sm text-gray-600 mb-4">{member.name} ({member.username})</p>

                <div className="space-y-3">
                    <label className="flex items-center space-x-3">
                        <input type="checkbox" checked={canEdit} onChange={(e) => setCanEdit(e.target.checked)} className="h-4 w-4" />
                        <span>파일/폴더 편집</span>
                    </label>
                    <label className="flex items-center space-x-3">
                        <input type="checkbox" checked={canDelete} onChange={(e) => setCanDelete(e.target.checked)} className="h-4 w-4" />
                        <span>파일/폴더 삭제</span>
                    </label>
                    <label className="flex items-center space-x-3">
                        <input type="checkbox" checked={canInvite} onChange={(e) => setCanInvite(e.target.checked)} className="h-4 w-4" />
                        <span>다른 팀원 초대</span>
                    </label>
                </div>

                <div className="flex justify-end space-x-2 mt-6">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">취소</button>
                    <button onClick={handleSave} className="px-4 py-2 bg-blue-600 text-white rounded-md">
                        저장
                    </button>
                </div>
            </div>
        </div>
    );
};

const VersionHistoryModal = ({ isOpen, onClose, file, onRestore }) => {
    const [history, setHistory] = React.useState([]);

    React.useEffect(() => {
        if (isOpen && file) {
            API.getVersionHistory(file.id)
                .then(setHistory)
                .catch(err => alert(err.message));
        }
    }, [isOpen, file]);

    if (!isOpen || !file) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-40 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-[600px] shadow-xl">
                <h2 className="text-lg font-semibold mb-4">버전 기록: {file.name}</h2>
                <div className="h-80 overflow-y-auto border rounded">
                    <table className="w-full text-sm text-left">
                        <thead className="bg-gray-50 sticky top-0">
                        <tr>
                            <th className="p-3">작업자</th>
                            <th className="p-3">저장 시간</th>
                            <th className="p-3">작업</th>
                        </tr>
                        </thead>
                        <tbody>
                        {history.map(ver => (
                            <tr key={ver.versionId} className="border-b">
                                <td className="p-3 font-semibold">{ver.editorName}</td>
                                <td className="p-3">{new Date(ver.createdAt).toLocaleString()}</td>
                                <td className="p-3">
                                    <button
                                        onClick={() => onRestore(ver.versionId)}
                                        className="px-3 py-1 bg-gray-200 text-xs rounded hover:bg-gray-300"
                                    >
                                        이 버전으로 복원
                                    </button>
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
                <div className="flex justify-end mt-4">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">닫기</button>
                </div>
            </div>
        </div>
    );
};

const PreviewModal = ({ isOpen, onClose, file, onEdit }) => {
    const [previewContent, setPreviewContent] = React.useState(null);
    const [isLoading, setIsLoading] = React.useState(true);

    const isEditable = file && ['txt', 'log', 'md'].includes(file.name.split('.').pop().toLowerCase());

    React.useEffect(() => {
        if (isOpen && file) {
            setIsLoading(true);
            const extension = file.name.split('.').pop().toLowerCase();

            if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(extension)) {
                API.getFileBlobUrl(file.id)
                    .then(url => setPreviewContent({ type: 'image', url }))
                    .catch(err => setPreviewContent({ type: 'error', message: err.message }))
                    .finally(() => setIsLoading(false));
            } else if (['txt', 'log', 'md'].includes(extension)) {
                API.getFileContent(file.id)
                    .then(text => setPreviewContent({ type: 'text', content: text }))
                    .catch(err => setPreviewContent({ type: 'error', message: err.message }))
                    .finally(() => setIsLoading(false));
            } else if (extension === 'pdf') {
                const pdfUrl = `/api/files/view/${file.id}`;
                setPreviewContent({ type: 'pdf', url: pdfUrl });
                setIsLoading(false);
            } else if (['docx','xlsx','xls','pptx','ppt'].includes(extension)) {
                API.getDocxPreviewUrl(file.id)
                    .then(sasUrl => {
                        const officeUrl = `https://view.officeapps.live.com/op/embed.aspx?src=${encodeURIComponent(sasUrl)}`;
                        setPreviewContent({ type: 'office', url: officeUrl });
                    })
                    .catch(err => setPreviewContent({ type: 'error', message: err.message }))
                    .finally(() => setIsLoading(false));
            }
            else {
                setPreviewContent({ type: 'unsupported', message: '이 파일 형식은 미리보기를 지원하지 않습니다.' });
                setIsLoading(false);
            }
        }
    }, [isOpen, file]);

    const renderPreview = () => {
        if (isLoading) return <p>로딩 중...</p>;
        if (!previewContent) return <p>콘텐츠를 불러올 수 없습니다.</p>;

        switch (previewContent.type) {
            case 'image':
                return <img src={previewContent.url} alt={file.name} className="max-w-full max-h-full object-contain" />;
            case 'text':
                return <pre className="w-full h-full p-4 text-sm whitespace-pre-wrap overflow-y-auto">{previewContent.content}</pre>;
            case 'pdf':
                return <iframe src={previewContent.url} width="100%" height="100%" title={file.name}></iframe>;
            case 'error':
                return <p className="text-red-500">오류: {previewContent.message}</p>;
            case 'unsupported':
                return <p>{previewContent.message}</p>;
            case 'office':
                return <iframe src={previewContent.url} width="100%" height="100%" title={file.name}></iframe>;

            default:
                return <p>알 수 없는 콘텐츠 타입입니다.</p>;
        }
    };

    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-60 flex justify-center items-center z-50" onClick={onClose}>
            <div className="bg-white p-4 rounded-lg shadow-2xl w-3/4 h-3/4 flex flex-col" onClick={(e) => e.stopPropagation()}>
                <div className="flex justify-between items-center border-b pb-2 mb-2">
                    <h2 className="text-lg font-semibold">{file?.name}</h2>
                    <div>
                        {isEditable && (
                            <button onClick={() => onEdit(file)} className="px-4 py-2 mr-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700">
                                편집/번역
                            </button>
                        )}
                        <button onClick={onClose} className="px-4 py-2 bg-gray-200 text-sm rounded-lg hover:bg-gray-300">
                            닫기
                        </button>
                    </div>
                </div>
                <div className="flex-grow flex items-center justify-center overflow-auto">
                    {renderPreview()}
                </div>
            </div>
        </div>
    );
};

const AiResultModal = ({ isOpen, onClose, title, content, isLoading }) => {
    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex justify-center items-center z-50">
            <div className="bg-white p-6 rounded-lg w-[600px] h-[400px] shadow-xl flex flex-col">
                <h2 className="text-lg font-semibold mb-4">{title}</h2>
                <div className="flex-grow border rounded p-3 overflow-y-auto bg-gray-50 whitespace-pre-wrap">
                    {isLoading ? "결과를 생성 중입니다..." : content}
                </div>
                <div className="flex justify-end mt-4">
                    <button onClick={onClose} className="px-4 py-2 bg-gray-200 rounded-md hover:bg-gray-300">닫기</button>
                </div>
            </div>
        </div>
    );
};


const WelcomeInfoModal = ({ isOpen, onClose }) => {
    if (!isOpen) return null;

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex justify-center items-center z-50">
            <div className="bg-white p-8 rounded-lg w-[550px] shadow-2xl flex flex-col">
                <h2 className="text-2xl font-bold mb-4 text-center text-blue-600">SmartCollab에 오신 것을 환영합니다!</h2>
                <div className="text-sm text-gray-800 space-y-4 mb-8">
                    <div>
                        <h3 className="font-semibold text-md mb-1">[ 🚀 프로젝트 개요 ]</h3>
                        <p className="pl-2">
                            • 본 프로젝트는 포트폴리오용으로 제작된 클라우드 협업 시스템입니다.<br/>
                            • 회원가입 후 파일 업로드, 팀 생성, 실시간 채팅 등 모든 기능을 자유롭게 테스트하실 수 있습니다.
                        </p>
                    </div>
                    <div>
                        <h3 className="font-semibold text-md mb-1">[ 👨‍💻 테스트용 계정 ]</h3>
                        <p className="pl-2">• 아이디: <strong>test1</strong> / 비밀번호: <strong>Tester123@</strong></p>
                        <p className="pl-2">• 아이디: <strong>test2</strong> / 비밀번호: <strong>Tester123@</strong></p>
                        <p className="pl-2">• 아이디: <strong>test3</strong> / 비밀번호: <strong>Tester123@</strong></p>
                    </div>
                    <div>
                        <h3 className="font-semibold text-md mb-1">[ ⚠️ 주의사항 ]</h3>
                        <p className="pl-2">• 본 시스템은 주기적으로 데이터가 초기화될 수 있으며, 업로드된 파일은 영구 보관되지 않습니다.</p>
                    </div>
                </div>
                <div className="flex justify-center">
                    <button
                        onClick={onClose}
                        className="px-6 py-2 bg-blue-600 text-white font-semibold rounded-lg hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-opacity-50"
                    >
                        확인하고 시작하기
                    </button>
                </div>
            </div>
        </div>
    );
};