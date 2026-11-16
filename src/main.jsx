import React,{useEffect,useRef,useState} from 'react';
import{createRoot}from'react-dom/client';
import './style.css';
function App(){
 const [role,setRole]=useState(''),[mode,setMode]=useState('ocr'),[code,setCode]=useState(''),[entered,setEntered]=useState(''),[status,setStatus]=useState('Выбери режим устройства'),[image,setImage]=useState(''),[weight,setWeight]=useState(''),[verified,setVerified]=useState(false),[raw,setRaw]=useState(''),[error,setError]=useState(''),[torch,setTorch]=useState(false);
 const roleRef=useRef(role);roleRef.current=role;
 useEffect(()=>{window.onNativeEvent=({type,value})=>{if(type==='code')setCode(value);if(type==='status')setStatus(value);if(type==='error')setError(value);if(type==='frame')setImage('data:image/jpeg;base64,'+value);if(type==='weight')setWeight(value);if(type==='raw')setRaw(value);if(type==='verified')setVerified(value==='true');};return()=>{delete window.onNativeEvent}},[]);
 function startSender(){setRole('yin');setCode('');setError('');setStatus('Подготовка камеры…');window.Native?.startSender();}
 function connect(){if(!/^\d{6}$/.test(entered)){setError('Введи 6 цифр');return;}setError('');setRole('yang');setStatus('Поиск камеры…');window.Native?.connect(entered);}
 function back(){window.Native?.stop();setRole('');setImage('');setWeight('');setCode('');setVerified(false);setStatus('Отключено');setError('');}
 return <main><div className="logo">☯</div><div className="brand">YinYang Scale</div><p className="sub">Два телефона · одни весы · без интернета</p>
 {!role?<><div className="card"><h2>Выбери роль</h2><div className="roles"><button className="role" onClick={startSender}><span>📷</span>ИНЬ<br/><small>Камера</small></button><button className="role" onClick={()=>{setRole('yang');setError('')}}><span>📱</span>ЯН<br/><small>Экран</small></button></div></div><div className="card"><div className="muted">Оба телефона должны быть подключены к одной Wi-Fi-сети или точке доступа одного из них. Интернет не требуется.</div></div></>:
 <><div className="card"><h2>{role==='yin'?'📷 Инь · камера':'📱 Ян · приёмник'}</h2>
 {role==='yin'?<><div className="muted">Со второго телефона введи этот код:</div><div className="code">{code||'••••••'}</div><div className="small muted">Наведите заднюю камеру на цифры весов. Для автономной работы держите приложение открытым.</div><button className="secondary" style={{marginTop:15}} onClick={()=>{setTorch(!torch);window.Native?.setTorch(!torch)}}>Фонарик: {torch?'Вкл':'Выкл'}</button></>:
 <>{!image&&<><div className="muted">Введи код с телефона Инь</div><input className="input" type="tel" value={entered} maxLength={6} placeholder="000000" onChange={e=>setEntered(e.target.value.replace(/\D/g,'').slice(0,6))}/><button className="primary" onClick={connect}>Подключиться по коду</button></>}
 <div className="tabs"><button className={mode==='ocr'?'sel':''} onClick={()=>setMode('ocr')}>Автовывод</button><button className={mode==='video'?'sel':''} onClick={()=>setMode('video')}>Трансляция</button></div></>}
 <div className="status">{status}</div>{error&&<div className="status error">{error}</div>}
 {(role==='yin'||role==='yang'&&(!!image||!!weight))&&<div className="screen">{role==='yang'&&mode==='ocr'?<><div className="small muted">{verified?'✓ Показание стабильно':'⚠ Показание не подтверждено'}</div><div className="weight">{weight||'—'}</div><div className="units">кг</div></>:image?<img src={image} alt="Видео весов"/>:<div className="muted">Ожидание первого кадра…</div>}</div>}
 {role==='yang'&&mode==='ocr'&&raw&&<p className="small muted">OCR: {raw.slice(0,170)}</p>}
 <button className="primary danger" onClick={back}>Отключиться / Назад</button></div></>}
 <div className="footer">Показания распознавания приблизительны. Для торговых операций сверяйся с дисплеем поверенных весов.</div></main>
}
createRoot(document.getElementById('root')).render(<App/>);
